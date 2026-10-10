import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ComponentsService } from '../api/components.service';
import { apiErrorMessage } from '../api/api-base';
import { Runbook, RUNBOOK_LINK_KINDS, runbookFromContent, runbookLinkTarget } from '../runbook/runbook-model';
import { InspectoAlertComponent } from './alert.component';
import { ChipComponent } from './chip.component';

/**
 * The Runbook an Incident or Case was raised under (operator 2026-10-10): its title, summary, ordered steps with
 * their links, owner role and tags. Loads `GET /components/runbook/{id}`; the id comes from the object's
 * `runbook` attribute, which the Alert Rule stamps. A Runbook that has since been deleted is said so, not hidden.
 */
@Component({
    selector: 'inspecto-runbook-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterLink, InspectoAlertComponent, ChipComponent],
    template: `
        @if (runbook(); as r) {
            <section class="rounded-2xl border p-4" [attr.aria-labelledby]="'runbook-title-' + r.id">
                <h2 class="text-sm font-semibold" [id]="'runbook-title-' + r.id">Runbook: {{ r.title }}</h2>
                @if (r.summary) {
                    <p class="text-secondary mt-1 text-sm">{{ r.summary }}</p>
                }
                <ol class="mt-3 flex list-decimal flex-col gap-2 pl-5 text-sm">
                    @for (s of r.steps; track $index) {
                        <li>
                            {{ s.text }}
                            @if (s.link; as l) {
                                <a
                                    class="text-primary ml-1 underline"
                                    [routerLink]="target(l).commands"
                                    [queryParams]="target(l).queryParams"
                                    >Open {{ kindLabel(l.kind) }} {{ l.id }}</a
                                >
                            }
                        </li>
                    }
                </ol>
                @if (r.ownerRole || r.tags.length) {
                    <div class="mt-3 flex flex-wrap items-center gap-2 text-xs">
                        @if (r.ownerRole) {
                            <span class="text-secondary">Owner role: {{ r.ownerRole }}</span>
                        }
                        @for (t of r.tags; track t) {
                            <inspecto-chip>{{ t }}</inspecto-chip>
                        }
                    </div>
                }
            </section>
        } @else if (missing()) {
            <inspecto-alert variant="warning" title="Runbook not found">
                The rule names Runbook {{ runbookId() }}, which no longer exists in this Space.
            </inspecto-alert>
        } @else if (loadError(); as e) {
            <inspecto-alert variant="error">{{ e }}</inspecto-alert>
        }
    `,
})
export class RunbookPanelComponent {
    private api = inject(ComponentsService);

    readonly runbookId = input.required<string>();
    readonly runbook = signal<Runbook | null>(null);
    readonly missing = signal(false);
    readonly loadError = signal('');

    readonly target = runbookLinkTarget;

    constructor() {
        effect(() => {
            const id = this.runbookId();
            this.runbook.set(null);
            this.missing.set(false);
            this.loadError.set('');
            this.api.get('runbook', id).subscribe({
                next: (c) => this.runbook.set(runbookFromContent(id, c.content ?? {})),
                error: (e) =>
                    e?.status === 404
                        ? this.missing.set(true)
                        : this.loadError.set(apiErrorMessage(e, 'Could not load the Runbook')),
            });
        });
    }

    kindLabel(kind: string): string {
        return RUNBOOK_LINK_KINDS.find((k) => k.value === kind)?.label ?? kind;
    }
}
