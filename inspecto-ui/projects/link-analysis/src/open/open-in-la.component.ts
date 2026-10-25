import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage, LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { entitiesBase } from '@inspecto/link-analysis/entities/entity-profile-view';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { DeepLinkResolution, DeepLinkService, OpenLink, parseOpenLink } from './open-link';

type State = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; res: DeepLinkResolution };

/**
 * The "Open in Inspecto LA" landing page (LA-EDITIONS-1 section 4): another tool links here with an entity, an optional window
 * and an optional Investigation (see {@link parseOpenLink} for the contract). The link is checked in the browser, then on the
 * server; a bad link is refused with a readable reason. A masked entity is shown by its alias; a holder of
 * `canRevealLinkEntities` may reveal it here, one audited request. The page lives beside Link Analysis and the Entities pages
 * in both hosts (`/open`, `/studio/open`), so its links are its siblings.
 */
@Component({
    selector: 'inspecto-la-open',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, RouterLink, InspectoAlertComponent, InspectoPageHeaderComponent],
    template: `
        <inspecto-page-header title="Open in Inspecto LA" [subtitle]="subtitle()"></inspecto-page-header>
        <div class="flex max-w-3xl flex-col gap-4 p-6">
            @switch (state().kind) {
                @case ('loading') {
                    <p class="text-secondary m-0" role="status" data-testid="loading">Checking the link...</p>
                }
                @case ('error') {
                    <inspecto-alert variant="error" title="This link cannot be opened" data-testid="open-error">{{
                        errorText()
                    }}</inspecto-alert>
                }
                @case ('ready') {
                    @if (res(); as r) {
                        <dl class="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-2">
                            @if (r.investigation; as i) {
                                <dt class="font-semibold">Investigation</dt>
                                <dd class="m-0" data-testid="investigation">{{ i.title || i.id }}</dd>
                            }
                            @if (r.entity; as e) {
                                <dt class="font-semibold">Entity</dt>
                                <dd class="m-0" data-testid="entity">
                                    <span class="font-mono">{{ e.type }}: {{ revealed() ?? e.key }}</span>
                                    @if (e.masked && !revealed()) {
                                        <span class="text-secondary ml-2 text-xs" data-testid="masked"
                                            >masked - shown by its alias</span
                                        >
                                    }
                                </dd>
                            }
                            @if (r.window; as w) {
                                <dt class="font-semibold">Window</dt>
                                <dd class="m-0" data-testid="window">
                                    {{ w.from ?? 'the start' }} to {{ w.to ?? 'now' }}
                                </dd>
                            }
                        </dl>
                        @if (revealError(); as re) {
                            <inspecto-alert variant="error" data-testid="reveal-error">{{ re }}</inspecto-alert>
                        }
                        <div class="flex flex-wrap gap-2">
                            @if (r.investigation; as i) {
                                <a
                                    mat-flat-button
                                    color="primary"
                                    data-testid="open-investigation"
                                    [routerLink]="linkAnalysis()"
                                    [queryParams]="{ investigation: i.id }"
                                    >Open the Investigation</a
                                >
                            }
                            @if (r.entity; as e) {
                                @if (entityPages()) {
                                    <a
                                        mat-stroked-button
                                        data-testid="open-entity"
                                        [routerLink]="[...base(), 'entities', e.type, e.key]"
                                        [queryParams]="r.window ? { from: r.window.from, to: r.window.to } : null"
                                        >Open the Entity</a
                                    >
                                }
                                @if (e.masked && !revealed() && lens.canRevealLinkEntities()) {
                                    <button mat-stroked-button type="button" data-testid="reveal" (click)="reveal()">
                                        Reveal the value
                                    </button>
                                }
                            }
                        </div>
                    }
                }
            }
        </div>
    `,
})
export class OpenInLaComponent implements OnInit {
    private route = inject(ActivatedRoute);
    private svc = inject(DeepLinkService);
    private destroyRef = inject(DestroyRef);
    readonly lens = inject(LensService);
    /** LA-EDITIONS-1 ED-6: links from other tools are third-party integration, Standard and above (503 on Basic). */
    private readonly investigations = inject(LA_FEATURES).investigations;
    /** D-LAE4: the Entity profile pages are Standard and up; without them there is no Entity page to open. */
    private readonly flags = inject(LA_FEATURES).flags;
    readonly entityPages = computed(() => this.flags?.()['entityPages'] === true);

    readonly state = signal<State>({ kind: 'loading' });
    readonly revealed = signal<string | null>(null);
    readonly revealError = signal<string | null>(null);
    private link: OpenLink | null = null;

    /** This page's parent: `['/']` in la-app, `['/', 'studio']` in the console. */
    readonly base = signal<string[]>(['/']);
    readonly linkAnalysis = computed(() => [...this.base(), 'link-analysis']);
    readonly res = computed(() => {
        const s = this.state();
        return s.kind === 'ready' ? s.res : null;
    });
    readonly errorText = computed(() => {
        const s = this.state();
        return s.kind === 'error' ? s.message : '';
    });
    readonly subtitle = computed(() =>
        this.state().kind === 'error' ? 'A link from another tool' : 'What a link from another tool names',
    );

    ngOnInit(): void {
        this.base.set(entitiesBase(this.route, 1));
        this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((pm) => {
            this.revealed.set(null);
            this.revealError.set(null);
            const parsed = parseOpenLink(pm);
            if ('error' in parsed) {
                this.link = null;
                this.state.set({ kind: 'error', message: parsed.error });
                return;
            }
            const link = parsed.link;
            if (!this.investigations()) {
                // Basic: the deep-link route is not in this edition; say so instead of a 503.
                this.link = null;
                this.state.set({
                    kind: 'error',
                    message:
                        'Opening a link from another tool is part of third-party integration, available in Standard and ' +
                        'above. This edition (Basic) is the Link Analysis explorer: open Link Analysis and search for the entity instead.',
                });
                return;
            }
            this.link = link;
            this.state.set({ kind: 'loading' });
            this.svc.resolve(link).subscribe({
                next: (res) => {
                    if (this.link === link) this.state.set({ kind: 'ready', res });
                },
                error: (e) => {
                    if (this.link === link)
                        this.state.set({
                            kind: 'error',
                            message: apiErrorMessage(e, 'The link could not be checked.'),
                        });
                },
            });
        });
    }

    /** One audited reveal of the masked entity; the raw value stays on this page and never enters a URL. */
    reveal(): void {
        const link = this.link;
        if (!link) return;
        this.revealError.set(null);
        this.svc.resolve(link, true).subscribe({
            next: (res) => this.revealed.set(res.entity?.revealed ?? null),
            error: (e) => this.revealError.set(apiErrorMessage(e, 'The value could not be revealed.')),
        });
    }
}
