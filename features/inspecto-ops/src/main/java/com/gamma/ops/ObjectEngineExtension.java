package com.gamma.ops;

import java.nio.file.Path;
import java.util.List;

/**
 * An optional module's hook into the operational-object engine (MODULE-REORG-P7): discovered through
 * {@code ServiceLoader} when a Space's engine boots, and handed the engine plus every config file found so it can
 * load the config kinds only it understands. {@code inspecto-case-management} uses it to read
 * {@code *_caserule.toon} into its Case Rule registry; with that module absent the files are simply never read.
 *
 * <p>Implementations must be fail-soft per file (warn and skip one bad document, never stop a Space starting),
 * exactly as the engine's own loaders are.
 */
public interface ObjectEngineExtension {

    /** Load this module's config kinds from {@code configPaths} into {@code service}. */
    void loadConfigs(ObjectService service, List<Path> configPaths);
}
