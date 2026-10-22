import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { OnboardingEventsService, OnboardingMetrics } from 'app/inspecto/onboarding/onboarding-events.service';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { apiErrorMessage, LensService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { DataSettings } from '@inspecto/link-analysis/add-data/add-data.model';
import { OnboardService } from '@inspecto/link-analysis/add-data/onboard.service';
import { LinkAnalysisLimitsComponent } from 'app/modules/admin/settings/link-analysis-limits.component';
import { LinkAnalysisSettingsComponent } from 'app/modules/admin/settings/link-analysis-settings.component';

/**
 * The sections of the Link Analysis app's Settings, one component each. The console draws the same settings and limits
 * in ONE page (Settings > Link Analysis); here each is a section of its own so the menu can say who it is for: a user's
 * own values, this Space's settings and limits, or the whole server's. Each shows the server's own answer: a control a
 * caller may not change is read-only, and the server refuses a save anyway.
 */

/** My browser: the user's own limits for this browser, at most the Space value. Needs no privilege. */
@Component({
    selector: 'la-app-my-browser-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoPageHeaderComponent, LinkAnalysisLimitsComponent],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="My browser"
                subtitle="Your own values for this browser only, at most the Space value. Nobody else sees them."
                [inset]="false"
            />
            <inspecto-link-analysis-limits [sections]="['browser']" />
        </div>
    `,
})
export class MyBrowserSettingsComponent {}

/** This Space's Link Analysis settings (masking, Entity Types, the index, risk weights, embed links). */
@Component({
    selector: 'la-app-space-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisSettingsComponent],
    template: `<inspecto-link-analysis-settings [withLimits]="false" />`,
})
export class SpaceSettingsComponent {}

/** This Space's analysis limits. */
@Component({
    selector: 'la-app-analysis-limits-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoPageHeaderComponent, LinkAnalysisLimitsComponent],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Analysis limits"
                subtitle="Limits for this Space, enforced by the server. Blank is the shipped default."
                [inset]="false"
            />
            <inspecto-link-analysis-limits [sections]="['analysis']" />
        </div>
    `,
})
export class AnalysisLimitsSettingsComponent {}

/** The install-wide host resources, shared by every Space on this server. */
@Component({
    selector: 'la-app-host-resources-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoPageHeaderComponent, LinkAnalysisLimitsComponent],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Host resources"
                subtitle="Install-wide limits shared by every Space on this server. Only a system administrator may change them."
                [inset]="false"
            />
            <inspecto-link-analysis-limits [sections]="['host']" />
        </div>
    `,
})
export class HostResourcesSettingsComponent {}

/** A duration in plain words: "45 s", "4 min 10 s", "2 h 5 min", "3 days". */
export function durationWords(seconds: number | null | undefined): string {
    if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return '';
    const s = Math.round(seconds);
    if (s < 60) return `${s} s`;
    if (s < 3600) return s % 60 ? `${Math.floor(s / 60)} min ${s % 60} s` : `${s / 60} min`;
    if (s < 86400) {
        const m = Math.floor((s % 3600) / 60);
        return m ? `${Math.floor(s / 3600)} h ${m} min` : `${Math.floor(s / 3600)} h`;
    }
    const d = Math.floor(s / 86400);
    return `${d} ${d === 1 ? 'day' : 'days'}`;
}

/** One milestone in words: "After 4 min 10 s (22 Oct 2026, 10:04)", or "Not yet". */
export function milestoneWords(at: string | null | undefined, seconds: number | null | undefined): string {
    if (!at) return 'Not yet';
    const after = durationWords(seconds);
    const when = new Date(at);
    const stamp = Number.isNaN(when.getTime())
        ? at
        : when.toLocaleString('en-GB', {
              day: 'numeric',
              month: 'short',
              year: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
          });
    return after ? `After ${after} (${stamp})` : stamp;
}

/** The steps people left unfinished, in words. */
const STEP_WORDS: Record<string, string> = {
    space: 'Creating a Space',
    data: 'Data',
    columns: 'What the columns hold',
    graph: 'First graph',
    list: 'A list',
    'add_data:file': 'Add data: choosing a file',
    'add_data:columns': 'Add data: checking the columns',
    'add_data:create': 'Add data: creating the Dataset',
};

/**
 * Getting started counters (LA-ONBOARD-1 OB-13, A-10): how first-time users fare on this server, for administrators,
 * read-only. Counted on this installation and never sent anywhere (`GET /onboarding/metrics`).
 */
@Component({
    selector: 'la-app-onboarding-counters-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent, InspectoPageHeaderComponent, InspectoSkeletonComponent],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Getting started counters"
                subtitle="How first-time users fare on this server. Counted here, never sent anywhere."
                [inset]="false"
            />
            @if (loaded()) {
                @if (metrics(); as m) {
                    @if (!m.recording) {
                        <inspecto-alert variant="info">
                            This server has no place to keep the counters (no server-wide settings folder), so nothing
                            is counted. Start it with a spaces folder or a system settings folder to count.
                        </inspecto-alert>
                    }
                    <dl class="m-0 grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2" data-testid="la-counters">
                        @for (row of rows(); track row.label) {
                            <div class="flex flex-col">
                                <dt class="text-secondary text-xs font-semibold uppercase tracking-wider">
                                    {{ row.label }}
                                </dt>
                                <dd class="m-0 tabular-nums">{{ row.value }}</dd>
                            </div>
                        }
                    </dl>
                    <section class="flex flex-col gap-1" aria-labelledby="la-counters-stopped">
                        <h2 id="la-counters-stopped" class="m-0 text-sm font-semibold">Where people stopped</h2>
                        @if (stopped().length) {
                            <ul class="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="la-counters-stopped">
                                @for (s of stopped(); track s.step) {
                                    <li>{{ s.words }}: {{ s.count }}</li>
                                }
                            </ul>
                        } @else {
                            <p class="text-secondary m-0 text-sm">Nobody has left a step unfinished yet.</p>
                        }
                    </section>
                } @else {
                    <inspecto-alert variant="info">
                        The counters could not be read: this server does not keep them, or you may not read them. Ask an
                        administrator of this server.
                    </inspecto-alert>
                }
            } @else {
                <inspecto-skeleton [lines]="4"></inspecto-skeleton>
            }
        </div>
    `,
})
export class OnboardingCountersSettingsComponent implements OnInit {
    private readonly events = inject(OnboardingEventsService);
    protected readonly loaded = signal(false);
    protected readonly metrics = signal<OnboardingMetrics | null>(null);
    protected readonly rows = signal<{ label: string; value: string }[]>([]);
    protected readonly stopped = signal<{ step: string; words: string; count: number }[]>([]);

    ngOnInit(): void {
        this.events.metrics().subscribe((m) => {
            this.metrics.set(m);
            if (m) {
                this.rows.set([
                    { label: 'First launch', value: milestoneWords(m.firstLaunch, null) },
                    { label: 'Launches', value: String(m.launches ?? 0) },
                    { label: 'First Space', value: milestoneWords(m.firstSpace, m.firstSpaceSeconds) },
                    { label: 'Spaces created', value: String(m.spacesCreated ?? 0) },
                    { label: 'First Dataset', value: milestoneWords(m.firstDataset, m.firstDatasetSeconds) },
                    { label: 'First graph', value: milestoneWords(m.firstGraph, m.firstGraphSeconds) },
                    { label: 'Space guide done', value: milestoneWords(m.guideCompleted, m.guideCompletedSeconds) },
                ]);
                this.stopped.set(
                    Object.entries(m.abandoned ?? {})
                        .map(([step, count]) => ({ step, words: STEP_WORDS[step] ?? step, count }))
                        .sort((a, b) => b.count - a.count || a.words.localeCompare(b.words)),
                );
            }
            this.loaded.set(true);
        });
    }
}

/**
 * This Space → **Add data** (LA-ONBOARD-1 OB-7, phase 8): whether people may add files here (`data.self_import`). With
 * sign-in it is off until an administrator turns it on, and the Add data screen sends people to an administrator - this is
 * that administrator's one switch (before phase 8 it existed only as `PUT /settings/data`). Read-only for anyone else; the
 * server decides in the end (`canAdminister`).
 */
@Component({
    selector: 'la-app-add-data-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent, InspectoPageHeaderComponent, InspectoSkeletonComponent, MatSlideToggleModule],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Add data"
                subtitle="Whether people may add files to this Space themselves, each becoming a Dataset."
                [inset]="false"
            />
            @if (settings(); as s) {
                <mat-slide-toggle
                    data-testid="la-self-import"
                    [checked]="s.selfImportInForce"
                    [disabled]="!canChange() || saving()"
                    (change)="set($event.checked)"
                    >People may add data in this Space</mat-slide-toggle
                >
                <p class="text-secondary m-0 text-sm" data-testid="la-self-import-why">
                    {{ s.selfImportInForce ? 'On' : 'Off' }}{{ s.selfImport === null ? ' (the default here)' : '' }}.
                    Without sign-in it is on by default; with sign-in it is off until an administrator turns it on, and
                    people also need the right to upload data.
                    @if (!canChange()) {
                        Only an administrator may change it.
                    }
                </p>
            } @else if (error()) {
                <inspecto-alert variant="info">{{ error() }}</inspecto-alert>
            } @else {
                <inspecto-skeleton [lines]="2"></inspecto-skeleton>
            }
        </div>
    `,
})
export class AddDataSettingsComponent implements OnInit {
    private readonly onboard = inject(OnboardService);
    private readonly session = inject(SessionService);
    private readonly lens = inject(LensService);
    /** Whether this person may change it (no sign-in, or `canAdminister`; the server answers the same). */
    protected readonly canChange = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());
    protected readonly settings = signal<DataSettings | null>(null);
    protected readonly error = signal('');
    protected readonly saving = signal(false);

    ngOnInit(): void {
        this.onboard.dataSettings().subscribe({
            next: (s) => this.settings.set(s),
            error: (e) => this.error.set(apiErrorMessage(e, 'This Space does not say whether people may add data.')),
        });
    }

    protected set(on: boolean): void {
        this.saving.set(true);
        this.onboard.setDataSettings(on).subscribe({
            next: (s) => {
                this.settings.set(s);
                this.saving.set(false);
            },
            error: (e) => {
                this.saving.set(false);
                this.error.set(apiErrorMessage(e, 'The change was refused.'));
                this.settings.set(null);
            },
        });
    }
}
