package {{packageName}};

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentReader;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;
import com.gamma.consignment.SummaryEmitter;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Consignment Processor is discovered by a plain {@code ServiceLoader} over the classpath, so it
 * needs no pack harness — construct the context and call it. The fake below refuses the surfaces
 * these two paths never reach; widen it as the processor grows, and let it keep refusing the rest.
 */
class {{className}}ProcessorTest {

    @Test
    void skipsWhenTheConsignmentRegisteredNoOutputs() throws Exception {
        Ctx ctx = new Ctx(List.of(), false);

        ProcessorResult result = new {{className}}Processor().process(ctx);

        assertEquals("SKIPPED", result.status());
    }

    @Test
    void dryRunPreviewsAndMutatesNothing() throws Exception {
        Ctx ctx = new Ctx(List.of(output()), true);

        ProcessorResult result = new {{className}}Processor().process(ctx);

        assertTrue(result.success());
        assertTrue(ctx.log.stream().anyMatch(l -> l.startsWith("dry run:")), ctx.log.toString());
    }

    private static ConsignmentOutput output() {
        return new ConsignmentOutput("c-1", "run-1", "events", null, "2026-01-01",
                "data/events.parquet", 10L, 1024L, "2026-01-01T00:00:00Z", 1,
                ConsignmentOutput.State.LIVE, null);
    }

    /** The narrow context, faked. Unreached surfaces throw rather than return a convenient lie. */
    private static final class Ctx implements ProcessorContext {
        private final List<ConsignmentOutput> outputs;
        private final boolean dryRun;
        private final List<String> log = new ArrayList<>();

        Ctx(List<ConsignmentOutput> outputs, boolean dryRun) {
            this.outputs = outputs;
            this.dryRun = dryRun;
        }

        @Override public String consignmentId()             { return "c-1"; }
        @Override public List<ConsignmentOutput> outputs()   { return outputs; }
        @Override public boolean dryRun()                    { return dryRun; }
        @Override public ConsignmentReader read()            { throw new UnsupportedOperationException("read()"); }
        @Override public SummaryEmitter summaries()          { throw new UnsupportedOperationException("summaries()"); }
        @Override public SignalEmitter signals()             { return (type, severity, payload) -> { }; }

        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv)  { log.add(m); }
                @Override public void warn(String m, Object... kv)  { log.add(m); }
                @Override public void error(String m, Throwable t, Object... kv) { log.add(m); }
            };
        }
    }
}
