import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ConditionGroup } from 'app/inspecto/query';
import { Widget } from '../widgets/widget-types';
import { Dataset } from '../datasets/dataset-types';
import { StaleMark } from 'app/inspecto/signal/stale-tiles';
import { DrillEvent, WidgetHostComponent } from '../widgets/widget-host.component';

/**
 * One dashboard tile — a thin wrapper around the shared {@link WidgetHostComponent} (the one render path,
 * also used by the widget gallery's thumbnails and the standalone view route), passing the dashboard's
 * cross-filter through and re-emitting drill-down clicks. The host + dataset are already loaded by the
 * dashboard editor, so this stays in pre-loaded mode — no extra fetch per tile.
 *
 * A host's own tile controls (the editor's drag / width / remove) project in as `[tileActions]` and land in the
 * tile card's header action set beside export, instead of floating over the title. It fills its grid cell, so
 * tiles in one row share a height.
 */
@Component({
    selector: 'app-dashboard-tile',
    standalone: true,
    imports: [WidgetHostComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block h-full min-w-0' },
    template: `<app-widget-host
        [widget]="widget()"
        [dataset]="dataset()"
        [filter]="filter()"
        [stale]="stale()"
        (drill)="drill.emit($event)"
        ><ng-container tileActions><ng-content select="[tileActions]" /></ng-container
    ></app-widget-host>`,
})
export class DashboardTileComponent {
    readonly widget = input.required<Widget>();
    /** Absent for view-bound widgets (geo-map / link-analysis) — their saved view is the binding. */
    readonly dataset = input<Dataset | undefined>(undefined);
    readonly filter = input<ConditionGroup | null>(null);
    /** Resolved by the dashboard, not here — see WidgetHostComponent.stale. */
    readonly stale = input<StaleMark | null>(null);
    readonly drill = output<DrillEvent>();
}
