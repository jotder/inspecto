package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.util.CanonicalHash;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Platform Service {@code schema} (platform-services S1-5): read-only access to the space's reusable
 * {@code schema} components ({@code registry/schemas/*.toon}, indexed by {@link ComponentRegistry})
 * and their canonical fingerprints — the same {@link CanonicalHash} the engine pins into
 * {@code BatchManifest.schemaFingerprint} and {@code consignment_outputs.schema_fingerprint}.
 * Granted to a Run via a Job Type's {@code requires: [schema]} declaration.
 *
 * <h3>Dry-run contract (plan §3.4)</h3>
 * Read-only — unaffected by a dry run; the real service is handed through unchanged.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface SchemaAccess {

    /** The names of the schema components currently in the space's registry. */
    List<String> list();

    /** The named schema's content (the parsed {@code .toon} map, read-only), or empty when unknown. */
    Optional<Map<String, Object>> get(String name);

    /** The named schema's canonical fingerprint ({@link CanonicalHash#sha256}), or empty when unknown. */
    Optional<String> fingerprint(String name);

    /** The production implementation over a live {@link ComponentRegistry} view — the supplier is
     *  invoked per call so an operator's registry edit is visible without a restart. */
    static SchemaAccess over(Supplier<ComponentRegistry> registry) {
        return new SchemaAccess() {
            @Override public List<String> list() {
                return registry.get().ofType("schema").stream()
                        .map(ComponentRegistry.Component::name).toList();
            }
            @Override public Optional<Map<String, Object>> get(String name) {
                return registry.get().resolve("schema/" + name)
                        .map(ComponentRegistry.Component::content);
            }
            @Override public Optional<String> fingerprint(String name) {
                return get(name).map(CanonicalHash::sha256);
            }
        };
    }
}
