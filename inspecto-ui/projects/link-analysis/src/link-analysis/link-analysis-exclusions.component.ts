import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import type { ExclusionListSummary } from '@inspecto/link-analysis/api/inv.service';
import { GROW_GUIDE, ListInForce, PackTerms, RemovedRow, hintSeen, markHintSeen } from './grow-exclude';

/**
 * LA-GROW-EXCLUDE-1 §3.3 — the **Exclusions** panel beside the graph: the lists in force as chips (name, who keeps it,
 * how many it removed), each switchable (on = `excludeBy`, off = the `undo` of its step — both recorded), "Show what was
 * removed" (a restricted list's members are counted, never listed — GE-8), and "N … hidden by M lists". With no list yet
 * it teaches: "Create your first list", and the category lists the administrator set up. Presentational.
 */
@Component({
    selector: 'inspecto-link-analysis-exclusions',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent, PenFieldComponent],
    template: `
        <section
            class="flex max-h-[70vh] w-[22rem] max-w-[calc(100vw-2rem)] flex-col gap-2 overflow-y-auto rounded-lg border p-3 shadow-lg"
            style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
            role="region"
            aria-labelledby="la-excl-title"
            data-testid="la-exclusions"
            (keydown.escape)="closed.emit()"
        >
            <div class="flex items-center gap-2">
                <mat-icon svgIcon="heroicons_outline:no-symbol" aria-hidden="true"></mat-icon>
                <h2 id="la-excl-title" class="m-0 text-sm font-semibold" matTooltip="Exclusion lists and excluded items">
                    Hidden by lists
                </h2>
                <button mat-icon-button class="ml-auto" type="button" aria-label="Close Hidden by lists" (click)="closed.emit()">
                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                </button>
            </div>

            @if (showHint()) {
                <div class="rounded-md border p-2 text-xs" style="border-color: var(--gamma-border)" data-testid="la-excl-hint">
                    @if (mode() === 'investigation') {
                        <p class="m-0">
                            An exclusion list keeps {{ t().nodes }} out of every step. Category lists come from your
                            administrator; analyst lists, such as call centres, are yours.
                        </p>
                        <p class="m-0">Switching a list on or off is one recorded step, and Undo takes it back.</p>
                    } @else {
                        <p class="m-0">
                            Exclude… hides {{ t().nodes }} from this graph only (not recorded); Show them again brings
                            them back. A list you add them to is kept, and applies to every step of an Investigation.
                        </p>
                    }
                    <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                    <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                </div>
            }

            @if (mode() === 'investigation') {
                <p class="m-0 text-sm" role="status" data-testid="la-excl-line">{{ line() }}</p>
                @if (inForce().length) {
                    <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Lists in force" data-testid="la-excl-in-force">
                        @for (l of inForce(); track l.listId) {
                            <li class="flex flex-wrap items-center gap-1 text-xs">
                                <inspecto-chip variant="outline" tone="primary">{{ l.title }}</inspecto-chip>
                                <inspecto-chip variant="soft" [matTooltip]="ownerHint(l.ownerKind)">{{ ownerLabel(l.ownerKind) }}</inspecto-chip>
                                <span [matTooltip]="l.restricted ? 'A restricted list: the count is rounded and its members are not shown' : ''"
                                    >{{ l.restricted ? 'about ' : '' }}{{ l.removed.toLocaleString('en') }} removed</span
                                >
                                <button
                                    mat-button
                                    type="button"
                                    class="ml-auto"
                                    [disabled]="!!(whyNot() || l.switchOffWhyNot)"
                                    [disabledInteractive]="true"
                                    [matTooltip]="whyNot() || l.switchOffWhyNot || 'Switch it off — undoes its step (recorded)'"
                                    [attr.aria-label]="'Switch off ' + l.title"
                                    (click)="!(whyNot() || l.switchOffWhyNot) && switchOff.emit(l.listId)"
                                >
                                    Switch off
                                </button>
                            </li>
                        }
                    </ul>
                }
                @if (available().length) {
                    <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Not in force</h3>
                    <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Lists not in force">
                        @for (l of available(); track l.id) {
                            <li class="flex flex-wrap items-center gap-1 text-xs">
                                <span>{{ l.title }}</span>
                                <inspecto-chip variant="soft" [matTooltip]="ownerHint(l.ownerKind)">{{ ownerLabel(l.ownerKind) }}</inspecto-chip>
                                <button
                                    mat-button
                                    type="button"
                                    class="ml-auto"
                                    [disabled]="!!whyNot()"
                                    [disabledInteractive]="true"
                                    [matTooltip]="whyNot() || 'Apply the list — one recorded step (exclude by list)'"
                                    [attr.aria-label]="'Switch on ' + l.title"
                                    (click)="!whyNot() && switchOn.emit(l.id)"
                                >
                                    Switch on
                                </button>
                            </li>
                        }
                    </ul>
                }
                @if (whyNot(); as why) {
                    <p class="text-secondary m-0 text-xs" data-testid="la-excl-why">{{ why }}</p>
                }
                @if (removedCount()) {
                    <button
                        mat-button
                        type="button"
                        class="self-start"
                        [attr.aria-expanded]="showRemoved()"
                        aria-controls="la-excl-removed"
                        (click)="showRemoved.set(!showRemoved())"
                    >
                        {{ showRemoved() ? 'Hide' : 'Show' }} what was removed
                    </button>
                    @if (showRemoved()) {
                        <div id="la-excl-removed" class="flex flex-col gap-1">
                            @if (removed().rows.length) {
                                <table class="w-full text-left text-xs" data-testid="la-excl-table">
                                    <caption class="sr-only">Excluded {{ t().nodes }}</caption>
                                    <thead>
                                        <tr>
                                            <th scope="col" class="pr-2">{{ nodeHeading() }}</th>
                                            <th scope="col" class="pr-2">Why</th>
                                            <th scope="col">Step</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (r of removed().rows.slice(0, 200); track r.id + ':' + r.step) {
                                            <tr>
                                                <td class="pr-2 font-mono">{{ r.id }}</td>
                                                <td class="pr-2">{{ r.why }}</td>
                                                <td class="tabular-nums">{{ r.step }}</td>
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            }
                            @if (removed().restrictedCount) {
                                <p class="text-secondary m-0 text-xs" data-testid="la-excl-restricted">
                                    About {{ removed().restrictedCount.toLocaleString('en') }} more were removed by restricted
                                    lists — their members are not shown.
                                </p>
                            }
                        </div>
                    }
                }
            } @else {
                <p class="m-0 text-sm" role="status" data-testid="la-excl-basic-line">
                    {{ basicHidden().toLocaleString('en') }} {{ basicHidden() === 1 ? t().node : t().nodes }} hidden on
                    this graph (not recorded).
                </p>
                @if (basicHidden()) {
                    <button mat-button type="button" class="self-start" (click)="showAgain.emit()">Show them again</button>
                }
            }

            @if (lists() !== null && !lists()!.length) {
                <div class="flex flex-col gap-2 rounded-md border p-2" style="border-color: var(--gamma-border)" data-testid="la-excl-first">
                    <p class="m-0 text-xs">
                        No exclusion list yet. A list keeps {{ t().nodes }} you never want to see — call centres, test
                        {{ t().nodes }} — out of every step.
                    </p>
                    <inspecto-pen-field label="List name" [formControl]="newTitle" placeholder="e.g. Call centres" [maxlength]="200"></inspecto-pen-field>
                    <button
                        mat-stroked-button
                        type="button"
                        class="self-start"
                        [disabled]="!!createWhyNot()"
                        [disabledInteractive]="true"
                        [matTooltip]="createWhyNot()"
                        (click)="create()"
                    >
                        Create your first list
                    </button>
                    @if (createWhyNot(); as why) {
                        <p class="text-secondary m-0 text-xs">{{ why }}</p>
                    }
                </div>
            } @else if (categoryLists().length) {
                <p class="text-secondary m-0 text-xs" data-testid="la-excl-categories">
                    Category lists set up by your administrator: {{ categoryTitles() }}.
                </p>
            }
        </section>
    `,
})
export class LinkAnalysisExclusionsComponent {
    readonly mode = input<'investigation' | 'basic'>('investigation');
    readonly inForce = input<ListInForce[]>([]);
    readonly line = input('');
    readonly removed = input<{ rows: RemovedRow[]; restrictedCount: number }>({ rows: [], restrictedCount: 0 });
    /** Every exclusion list of the Space; null = not readable here (the list sections are then not shown). */
    readonly lists = input<ExclusionListSummary[] | null>(null);
    readonly basicHidden = input(0);
    readonly terms = input.required<PackTerms>();
    /** Why a switch cannot run (no write capability); empty = it can. */
    readonly whyNot = input('');
    /** Why a list cannot be created here; empty = it can. */
    readonly createWhyNot = input('');
    readonly switchOff = output<string>();
    readonly switchOn = output<string>();
    readonly showAgain = output<void>();
    readonly createList = output<string>();
    readonly closed = output<void>();

    readonly showHint = signal(!hintSeen('exclusions'));
    readonly guide = GROW_GUIDE;
    readonly showRemoved = signal(false);
    readonly newTitle = new FormControl('', { nonNullable: true });
    readonly t = computed(() => this.terms());
    readonly nodeHeading = computed(() => {
        const n = this.t().node;
        return n.charAt(0).toUpperCase() + n.slice(1);
    });
    readonly available = computed(() => {
        const on = new Set(this.inForce().map((l) => l.listId));
        return (this.lists() ?? []).filter((l) => !on.has(l.id));
    });
    readonly removedCount = computed(() => this.removed().rows.length + this.removed().restrictedCount);
    readonly categoryLists = computed(() => (this.lists() ?? []).filter((l) => l.ownerKind === 'admin'));
    readonly categoryTitles = computed(() => this.categoryLists().map((l) => l.title).join(', '));

    ownerLabel(kind: 'admin' | 'analyst' | null | undefined): string {
        return kind === 'admin' ? 'Category' : kind === 'analyst' ? 'Analyst' : 'List';
    }

    dismissHint(): void {
        markHintSeen('exclusions');
        this.showHint.set(false);
    }

    ownerHint(kind: 'admin' | 'analyst' | null | undefined): string {
        return kind === 'admin'
            ? 'A category list, kept by an administrator'
            : kind === 'analyst'
              ? 'An analyst list, kept by its owner and the Investigation team'
              : 'An exclusion list';
    }

    create(): void {
        const title = this.newTitle.value.trim();
        if (this.createWhyNot() || !title) return;
        this.createList.emit(title);
        this.newTitle.reset();
    }
}
