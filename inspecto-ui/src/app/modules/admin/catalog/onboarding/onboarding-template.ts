import { StreamBundle, StreamImportPlan, planStreamImport } from 'app/inspecto/transfer/stream-bundle';

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * The import plan for **Start from a template** in the onboarding create dialog: a runnable copy of a
 * pipeline template (`template: true`, written by the editor's *Save as template*), built on the same
 * stream-bundle path Import and Duplicate use — the bundle is the template's export, `source.name` is
 * the template's id.
 *
 * {@link planStreamImport} already re-stamps the identity, re-derives `dirs` and lands the draft
 * inactive. On top of that a template copy must undo what `save-as-template` stamped
 * (`PipelineSettingsRoutes.neutralizeForTemplate`), which a plain bundle carries through verbatim:
 * - `template: true` — kept, the copy could never go live (the parser refuses `template` + `active`).
 * - `stream`, `collector.id` (legacy `source.id`) = the template's id — kept, every copy of one
 *   template would join the same Catalog Stream and share one acquisition-ledger dedup key.
 * - `output.ducklake.data_path` inside `data/templates/<id>/` — kept, every copy writes the same lake.
 * Each is rewritten only when it still holds the template's stamp, so a value the author set
 * deliberately on the template travels as authored.
 */
export function planTemplateCopy(
    bundle: StreamBundle,
    opts: { name: string; label?: string; missing?: string[] },
): StreamImportPlan {
    const plan = planStreamImport(bundle, { name: opts.name });
    const p = plan.pipeline;
    const tplId = bundle.source.name;
    const id = String(p['id']);

    delete p['template'];
    if (p['stream'] === tplId) p['stream'] = id;

    const colKey = isRecord(p['collector']) ? 'collector' : isRecord(p['source']) ? 'source' : null;
    if (colKey) {
        const col = p[colKey] as Record<string, unknown>;
        if (col['id'] === tplId) p[colKey] = { ...col, id };
    }

    const output = p['output'];
    if (isRecord(output) && isRecord(output['ducklake'])) {
        const lake = output['ducklake'];
        const path = String(lake['data_path'] ?? '');
        if (path.includes(`data/templates/${tplId}/`)) {
            // The copy's own home — the parent of the `dirs.database` the plan just derived.
            const database = String((p['dirs'] as Record<string, unknown>)['database']);
            const home = database.replace(/\/database$/, '');
            p['output'] = { ...output, ducklake: { ...lake, data_path: `${home}/ducklake` } };
        }
    }

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
