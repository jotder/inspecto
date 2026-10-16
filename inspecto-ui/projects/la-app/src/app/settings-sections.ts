import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
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
