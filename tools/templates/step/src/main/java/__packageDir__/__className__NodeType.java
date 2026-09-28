package {{packageName}};

import com.gamma.pipeline.ExecutionMode;
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineNodeType;
import com.gamma.pipeline.PipelineRel;

import java.util.Optional;
import java.util.Set;

/**
 * The <b>descriptor</b> half of the {@code transform.{{typeSuffix}}} Step kind: it makes the Step exist in
 * the palette, and {@code PipelineValidator} enforces the relationships declared here.
 */
public final class {{className}}NodeType implements PipelineNodeType {

    /** The discriminator, stored verbatim in the pipeline config. A Step kind must be {@code transform.*}. */
    public static final String TYPE = "transform.{{typeSuffix}}";

    /** Where rows missing the required column go — a declared reject stream, never a thrown exception. */
    public static final String MISSING = PipelineRel.reject("missing");

    @Override
    public String type() {
        return TYPE;
    }

    /** ⚠ Required: a pack Step runs as Java. A pack declaring {@code LOWERED} is rejected at load. */
    @Override
    public Optional<ExecutionMode> mode() {
        return Optional.of(ExecutionMode.EXECUTED);
    }

    @Override
    public NodeCategory category() {
        return NodeCategory.TRANSFORM;
    }

    @Override
    public String label() {
        return "{{name}}";
    }

    @Override
    public String description() {
        return "TODO: say what this Step does.";
    }

    /** Every relation the Step may emit. The engine refuses an emit to anything not listed here. */
    @Override
    public Set<String> emits() {
        return Set.of(PipelineRel.DATA, MISSING);
    }
}
