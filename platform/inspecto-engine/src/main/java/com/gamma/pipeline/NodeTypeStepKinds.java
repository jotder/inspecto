package com.gamma.pipeline;

import com.gamma.etl.StepKindRegistry;

/**
 * The engine's answer to {@link StepKindRegistry}: a {@code steps:} kind is usable when
 * {@code transform.<kind>} is a <b>registered</b> node type.
 *
 * <p>This is the inversion that lets the parser stay fail-closed at load while living below the
 * node-type registry: {@code inspecto-etl} asks the question, {@code inspecto-engine} answers it.
 *
 * <p>⚠ Registration only — it says nothing about whether the type can EXECUTE. A descriptor without a
 * {@code PipelineNodeExecutor} loads and then throws at run time, naming the missing provider. Both
 * halves are separate registrations on purpose; see {@code okf/backend/engine/node-types.md}.
 */
public final class NodeTypeStepKinds implements StepKindRegistry {

    @Override
    public boolean isKnown(String kind) {
        return kind != null && !kind.isBlank() && PipelineNodeTypes.isKnown("transform." + kind);
    }
}
