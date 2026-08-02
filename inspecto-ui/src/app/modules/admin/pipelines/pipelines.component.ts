import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';

/** Which lens the Pipelines pane shows: read-only **View**, or the authoring **Editor**. */
export type PipelinesViewMode = 'combined' | 'editor';

/**
 * Pipelines — a thin host around {@link PipelineEditorComponent}, which is the single surface for both
 * modes. **View is the same shell with `readOnly` set**, not a separate page: same toolbar, tabs,
 * palette, canvas and docks, minus every affordance that could change or save something. Keeping one
 * layout is the whole point — a reader who switches to Edit should not have to relearn the screen.
 *
 * <p>Nothing loads on arrival beyond the pipeline name list; the editor's Open dialog decides what is
 * fetched. `combined` is retained as the mode's id (and the route/query values that use it) even though
 * the old combined-topology rendering is gone.
 */
@Component({
    selector: 'app-pipelines',
    standalone: true,
    imports: [AiExplainComponent, MatButtonToggleModule, PipelineEditorComponent],
    templateUrl: './pipelines.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class PipelinesComponent {
    /** Which lens is shown: read-only View or the authoring Editor. */
    readonly mode = signal<PipelinesViewMode>('combined');

    setMode(m: PipelinesViewMode): void {
        this.mode.set(m);
    }
}
