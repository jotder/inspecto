package com.gamma.ops.cases;

import com.gamma.ops.ObjectEngineExtension;
import com.gamma.ops.ObjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/** Loads {@code *_caserule.toon} into the Space's Case Rule registry at engine boot (fail-soft per file). */
public final class CaseConfigExtension implements ObjectEngineExtension {

    private static final Logger log = LoggerFactory.getLogger(CaseConfigExtension.class);

    @Override
    public void loadConfigs(ObjectService service, List<Path> configPaths) {
        CaseOperations cases = CaseOperations.of(service);
        for (Path p : configPaths) {
            if (!p.getFileName().toString().endsWith("_caserule.toon")) continue;
            try {
                cases.registerCaseRule(CaseRule.load(p));
            } catch (Exception e) {
                log.warn("Skipping invalid case rule config {}: {}", p, e.getMessage());
            }
        }
    }
}
