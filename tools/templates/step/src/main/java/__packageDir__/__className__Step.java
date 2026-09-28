package {{packageName}};

import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.exec.StepContext;
import com.gamma.pipeline.exec.StepExecutor;
import com.gamma.pipeline.exec.StepInput;
import com.gamma.pipeline.exec.StepOutput;

/**
 * The <b>execution</b> half of {@code transform.{{typeSuffix}}}. The starting logic is a placeholder: every
 * row goes to {@code data}, except rows whose {@code required} column is NULL, which go to the
 * {@code reject:missing} stream. Replace it with the logic SQL cannot express — anything SQL can express
 * belongs in a built-in Step, which runs 30–40× faster than a row-at-a-time Java Step.
 *
 * <p>Config the Step reads (the node's authored attributes):
 * <pre>
 * required: msisdn          # the column that must not be NULL
 * timeout_seconds: 60       # optional — overrides the 5 min default; the system caps it at 30 min
 * </pre>
 *
 * <p>The rules the engine enforces:
 * <ul>
 *   <li>You never get a {@code Connection}. Read through {@code ctx.in()}; write through {@code ctx.emit()}.</li>
 *   <li>Throw to fail the batch — the engine then drops every table this Step created. Reject a row by
 *       emitting it to a declared {@code reject:<reason>} stream instead.</li>
 *   <li>Honour interrupts. At the deadline the engine interrupts this thread; a Step that keeps running is
 *       abandoned and its kind disabled until the pack is replaced.</li>
 *   <li>In a dry run ({@code ctx.dryRun()}), mutate nothing. Granted services already record instead of act.</li>
 * </ul>
 */
public final class {{className}}Step implements StepExecutor {

    @Override
    public String type() {
        return {{className}}NodeType.TYPE;
    }

    // To use a Platform Service, declare it here and look it up with ctx.services().get(...):
    // @Override public java.util.Set<String> requires() { return java.util.Set.of("notifications"); }

    @Override
    public void execute(StepContext ctx) throws Exception {
        Object required = ctx.attributes().get("required");
        if (required == null || required.toString().isBlank())
            throw new IllegalArgumentException("{{id}} node '" + ctx.nodeId() + "' needs a 'required' column");

        StepInput in = ctx.in();
        int column = in.column(required.toString().trim());
        StepOutput data = ctx.emit(PipelineRel.DATA);
        StepOutput missing = ctx.emit({{className}}NodeType.MISSING);
        while (in.next()) {
            in.getString(column);                       // TODO: your per-row logic
            (in.wasNull() ? missing : data).copyRow(in);
        }
    }
}
