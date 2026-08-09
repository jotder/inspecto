package {{packageName}};

import com.gamma.consignment.ConsignmentProcessor;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;
import com.gamma.consignment.SummaryEmitter;
import com.gamma.signal.Severity;

import java.util.List;
import java.util.Map;

/**
 * The {@code {{id}}} Consignment Processor: work that runs once a Consignment has landed, over that
 * Consignment's own data and nothing else.
 *
 * <p>{@link ProcessorContext} is deliberately narrower than a Job's context — read this
 * Consignment's outputs, query its relations read-only, emit summaries and Signals, log. It hands
 * out no engine object, so what a processor can reach never grows behind an author's back.
 */
public class {{className}}Processor implements ConsignmentProcessor {

    @Override
    public String id() {
        return "{{id}}";
    }

    @Override
    public ProcessorResult process(ProcessorContext ctx) throws Exception {
        // Failure is signalled by throwing — the framework turns it into a FAILED Run. This result
        // only separates "did the work" from "there was nothing to do".
        if (ctx.outputs().isEmpty())
            return ProcessorResult.skipped("no outputs registered for " + ctx.consignmentId());

        // A dry run must mutate nothing: report what would happen and stop. Do not fall through.
        if (ctx.dryRun()) {
            ctx.log().info("dry run: would process", "outputs", ctx.outputs().size());
            return ProcessorResult.ok("preview only");
        }

        try (var reader = ctx.read()) {
            for (String relation : reader.relations()) {
                List<Map<String, Object>> rows = reader.query("SELECT count(*) AS n FROM " + relation);
                ctx.log().info("TODO: do the work", "relation", relation, "rows", rows);
            }
        }

        // Every summary row must carry the reserved `count` measure — see SummaryEmitter.COUNT.
        ctx.log().info("summaries are emitted through", "emitter", SummaryEmitter.class.getSimpleName());

        ctx.signals().emit("{{id}}.completed", Severity.INFO, Map.of("outputs", ctx.outputs().size()));
        return ProcessorResult.ok("processed " + ctx.outputs().size() + " output(s)");
    }
}
