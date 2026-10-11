import { StreamBundle, StreamImportPlan, planStreamImport } from 'app/inspecto/transfer/stream-bundle';

/**
 * The import plan for **Start from a template** in the onboarding create dialog: a runnable copy of a
 * pipeline template (`template: true`, written by the editor's *Save as template*), built on the same
 * stream-bundle path Import uses — the bundle is the template's export, `source.name` is the
 * template's id.
 *
 * {@link planStreamImport} already re-stamps the identity, re-derives `dirs`, lands the draft inactive
 * and re-points `stream`, `collector.id` (legacy `source.id`) and `output.ducklake.data_path` at the
 * copy — which undoes those stamps of `save-as-template` (`PipelineSettingsRoutes.neutralizeForTemplate`).
 * The one stamp left to a template copy is `template: true` itself: kept, the copy could never go live
 * (the parser refuses `template` + `active`).
 */
export function planTemplateCopy(
    bundle: StreamBundle,
    opts: { name: string; label?: string; missing?: string[] },
): StreamImportPlan {
    const plan = planStreamImport(bundle, { name: opts.name });
    delete plan.pipeline['template'];

    const tplId = bundle.source.name;
    const notes = [
        `Starts from the template "${opts.label || tplId}" — a copy, not a link: later edits to the template ` +
            'reach nothing created from it. The copy is a runnable pipeline with its own Stream and collector id.',
    ];
    if (opts.missing?.length) {
        notes.push(
            `Could not read ${opts.missing.join(', ')} from the template — the draft is created without ` +
                (opts.missing.length === 1 ? 'it.' : 'them.'),
        );
    }
    plan.notes = [...notes, ...plan.notes];
    return plan;
}
