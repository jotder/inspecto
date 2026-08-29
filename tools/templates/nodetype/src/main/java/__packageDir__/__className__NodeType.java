package {{packageName}};

import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineNodeType;
import com.gamma.pipeline.PipelineRel;

import java.util.Set;

/**
 * The <b>descriptor</b> half of the {@code transform.{{typeSuffix}}} node type.
 *
 * <p>This is what makes the Step exist: the palette offers it, the inspector labels it, and
 * {@code PipelineValidator} enforces the relationships declared here — an outbound edge carrying a
 * relationship this type does not {@link #emits()} is rejected at validation.
 *
 * <p>⚠ <b>A node type needs BOTH halves.</b> This one on its own renders and validates and then fails
 * at run time; {@link {{className}}Executor} is what shapes the rows. They are separate ServiceLoader
 * registrations on purpose, so a provider implements only the half it needs — but a Step you intend to
 * run needs both, and their relationships must agree.
 */
public final class {{className}}NodeType implements PipelineNodeType {

    /**
     * The discriminator, stored verbatim in the pipeline config.
     *
     * <p>⚠ The {@code transform.} prefix is not decoration: the inline component-preview route refuses a
     * config whose {@code type} is not {@code transform.*}, so a differently-named type could never be
     * tested from the editor's own "Test this Step".
     */
    public static final String TYPE = "transform.{{typeSuffix}}";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeCategory category() {
        return NodeCategory.TRANSFORM;
    }

    @Override
    public String label() {
        return "Redact";
    }

    @Override
    public String description() {
        return "Replaces the tail of each listed column with a fixed mask, keeping a readable prefix.";
    }

    /** It reads a normal row set… */
    @Override
    public Set<String> accepts() {
        return Set.of(PipelineRel.DATA);
    }

    /**
     * …and produces one. ⚠ Declare exactly what the executor creates: a relation the executor emits but
     * the descriptor omits can be wired nowhere, and one declared here but never produced leaves a
     * downstream Step reading a table that does not exist.
     */
    @Override
    public Set<String> emits() {
        return Set.of(PipelineRel.DATA);
    }
}
