package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Run with parameters" offers only the parameters a Run honours. A Job that reads a key from its SAVED config
 * (cfg.opt / cfg.require / cfg.params) and never from ctx.params() declares it {@code configOnly}, so the dialog
 * does not offer an override that would be silently ignored; the authoring form still renders it.
 */
class ConfigOnlyParametersTest {

    private static List<JobTypeDescriptor> builtIns(Path dir) throws Exception {
        try (var s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            return js.jobTypes();
        }
    }

    private static JobTypeDescriptor type(List<JobTypeDescriptor> all, String id) {
        return all.stream().filter(t -> id.equals(t.id())).findFirst().orElseThrow();
    }

    @Test
    void configReadingBuiltInsDeclareEveryParameterConfigOnly(@TempDir Path dir) throws Exception {
        List<JobTypeDescriptor> all = builtIns(dir);
        for (String id : List.of("enrich", "report", "maintenance", "pipeline", "kpi.completeness",
                "space.comparison", "la.index.build", "sql.template")) {
            JobTypeDescriptor d = type(all, id);
            assertFalse(d.parameters().isEmpty(), id);
            assertTrue(d.parameters().stream().allMatch(ParameterDecl::configOnly), id + " must be config-only");
        }
    }

    @Test
    void runtimeHonouredParametersStayOverridable(@TempDir Path dir) throws Exception {
        List<JobTypeDescriptor> all = builtIns(dir);
        for (String id : List.of("alert.evaluate", "incident.open", "sample.hello")) {
            JobTypeDescriptor d = type(all, id);
            assertFalse(d.parameters().isEmpty(), id);
            assertTrue(d.parameters().stream().noneMatch(ParameterDecl::configOnly), id);
        }
    }

    @Test
    void sqlTemplateScannedTokensAreRuntimeAndItsStaticKeysAreConfigOnly() {
        JobConfig cfg = JobConfig.fromMap(Map.of("job", Map.of("name", "t", "type", "sql.template",
                "sql", "SELECT * FROM x WHERE d = $from", "sink_dataset", "out")));
        List<ParameterDecl> decls = new SqlTemplateJobType("data").parameters(cfg);
        Set<String> runtime = new java.util.TreeSet<>();
        decls.stream().filter(d -> !d.configOnly()).forEach(d -> runtime.add(d.name()));
        assertEquals(Set.of("from"), runtime, "only the SQL's $tokens are runtime parameters");
        assertEquals("Input Datasets", decls.stream().filter(d -> d.name().equals("sources")).findFirst()
                .orElseThrow().label());
    }

    @Test
    void theApiShapeCarriesTheFlag(@TempDir Path dir) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ps = (List<Map<String, Object>>) type(builtIns(dir), "report").toMap().get("parameters");
        assertEquals(Boolean.TRUE, ps.get(0).get("configOnly"));
    }
}
