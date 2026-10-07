package com.gamma.pipeline.exec;

import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;

import java.util.List;
import java.util.Map;

/**
 * Structural specs for registry <b>component kinds</b> that are not config TOON types, and the
 * production-preview judge that backs them — what {@code component_draft}/{@code config_schema} fall back
 * to when {@link ConfigSpecs#forType} has no answer.
 *
 * <p>⛔ <b>This lives BESIDE {@code ConfigSpecs.forType}, never inside it</b> (design
 * {@code ai-drafting-non-schema-design.md} §2.2, operator D3 2026-09-25). {@code forType}/{@code TYPES} is
 * the admission gate for config TOON types across the control plane ({@code /config/write},
 * {@code /config/spec}, bootstrap, the save gate …); a component kind added there would open a new write
 * surface for a type the engine never loads. It sits in {@code inspecto-engine} because a kind's judge is the
 * engine's own preview.
 *
 * <p><b>Only {@code transform}</b> is specced (operator D1). Its spec is deliberately thin — {@code type}
 * required and {@code transform.*} — because the operator vocabulary lives in {@link RowShaper} and D7
 * declined to re-declare it here. The TRUTH is {@link #previewFindings}: the draft runs through
 * {@link ComponentPreview#transform}, the path execution takes, so this judge cannot be stricter or looser
 * than a real run. Its findings are therefore UNANCHORED ({@code fieldPath ""}): the executor reports prose,
 * not a path.
 */
public final class ComponentSpecs {

    private ComponentSpecs() {}

    /** The component kinds with a spec here, in canonical order. Disjoint from {@link ConfigSpecs#TYPES}. */
    public static final List<String> KINDS = List.of("transform");

    /** The spec for component {@code kind}, or {@code null} when it has none. */
    public static ConfigSpec forKind(String kind) {
        if (kind == null) return null;
        return switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "transform" -> transform();
            default -> null;
        };
    }

    /** A {@code transform} registry component: {@code {type: transform.<op>, …operator config}}. */
    public static ConfigSpec transform() {
        return new ConfigSpec("transform", List.of(
                new FieldSpec("type", "Operator",
                        "The transform operator, `transform.<op>` (filter, route, dedup.marker, …). Every other "
                                + "key is that operator's own config; it is judged by previewing the draft over "
                                + "sample rows, not by this spec.",
                        FieldType.STRING, true, null, List.of(), "transform\\..+", null, null)),
                List.of());
    }

    /**
     * What the production preview says about {@code draft} over {@code sampleRows} (Option B): empty when the
     * preview ran and passed; one unanchored ERROR when it failed; one WARNING when there is no sample, so an
     * unpreviewed draft can never read as clean (D6). Empty for a kind with no preview judge.
     */
    public static List<Finding> previewFindings(String kind, Map<String, Object> draft,
                                                List<Map<String, Object>> sampleRows) {
        if (forKind(kind) == null) return List.of();
        if (sampleRows == null || sampleRows.isEmpty()) {
            return List.of(Finding.warning("",
                    "not previewed: no sample rows were supplied, so this draft has not been run. "
                            + "Provide sample rows to have it checked by the production preview."));
        }
        Object type = draft == null ? null : draft.get("type");
        String t = type == null ? "" : String.valueOf(type);
        try {
            ComponentPreview.transform(new PipelineNode("draft", t, draft, null), sampleRows);
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(Finding.error("", "preview failed: " + e.getMessage()));
        } catch (java.sql.SQLException | java.io.IOException e) {
            return List.of(Finding.error("",
                    "preview failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage())));
        }
    }
}
