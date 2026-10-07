package com.gamma.asn.plugin;

import java.util.Map;

/**
 * Per-pipeline state handed to providers at registration time. Replaces the legacy static
 * {@code TransformUtils.setCache} — N pipelines per JVM, each with its own lookups.
 */
@FunctionalInterface
public interface PluginContext {

    /**
     * The config's lookup tables ({@code @simpleLookup} in legacy tx.json):
     * table name → (key → value). Values are the parsed JSON scalars.
     */
    Map<String, Object> lookups();
}
