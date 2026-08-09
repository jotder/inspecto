package com.gamma.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The open registry of {@link JobTypeProvider}s that replaced the compiled-in {@link JobType} switch
 * in {@code JobService.build()} (P0, {@code docs/job-framework-design.md} §6.1). Keyed by type id;
 * the four built-ins register under their lowercased enum names ({@code enrich} / {@code report} /
 * {@code maintenance} / {@code pipeline}) so existing {@code *_job.toon} files load unchanged.
 *
 * <p>Every provider carries an <em>owner</em> tag: {@code null} for the permanent built-ins and
 * classpath ({@code ServiceLoader}) providers, or a Job Pack's key for hot-deployed types (P2c). Only
 * pack-owned types can be {@linkplain #deregister(String) deregistered} — a pack can never displace a
 * built-in (its id collides and is rejected at {@link #register(JobTypeProvider, String)}).
 */
final class JobTypeRegistry {

    private final Map<String, JobTypeProvider> providers = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();   // id -> owner (null = permanent)
    private final Map<String, String> sources = new LinkedHashMap<>();  // id -> builtin | classpath | pack:<owner>
    /** The host's Platform Service registry, against which a descriptor's {@code requires:} ids are
     *  validated fail-closed at registration (plan S1-2); {@code null} (bare test registries) means no
     *  service is available, so any third-party {@code requires:} refuses — never "accept and hope".
     *  Built-ins are the one exception when it is {@code null} (see {@link #register}). */
    private final PlatformServiceRegistry platform;

    JobTypeRegistry() {
        this(null);
    }

    JobTypeRegistry(PlatformServiceRegistry platform) {
        this.platform = platform;
    }

    /** Where a registered type came from — {@code builtin} \| {@code classpath} \| {@code pack:<owner>} —
     *  and which class implements it (§7.3). Answers "what is this job, where did it come from", which is
     *  unanswerable from the UI today. Provenance lives here rather than on {@link JobTypeDescriptor}
     *  because a provider cannot know its own: the pack owner is the registry's knowledge, not the
     *  descriptor author's. */
    record Provenance(String implClass, String source) {}

    /** Register a permanent built-in provider — never deregistered. */
    void register(JobTypeProvider provider) {
        register(provider, null, "builtin");
    }

    /** Register a permanent provider discovered on the classpath (an optional Maven module, §12.4). */
    void registerClasspath(JobTypeProvider provider) {
        register(provider, null, "classpath");
    }

    /** Register a provider owned by {@code owner} (a Job Pack key, or {@code null} for permanent). */
    void register(JobTypeProvider provider, String owner) {
        register(provider, owner, owner == null ? "builtin" : "pack:" + owner);
    }

    private void register(JobTypeProvider provider, String owner, String source) {
        // requires: resolves fail-closed at registration (S1-2): an unknown or build-absent service id
        // refuses the type here, pack-atomically — never an empty lookup at fire time.
        //
        // One exception, for BUILT-INS only (S1-7): when no Platform Service registry is wired at all
        // (`platform == null` — a lean/embedded JobService, e.g. an engine unit test), a built-in still
        // registers. Its service ships in the same build, so the id is not "unknown"; only the host
        // wiring is absent, and a built-in that declares a grant must tolerate an empty lookup anyway.
        // Third-party types stay strict: with no registry wired, any requires: refuses.
        boolean lenient = "builtin".equals(source) && platform == null;
        for (String req : provider.descriptor().requires()) {
            if (lenient) continue;
            if (platform == null || !platform.has(req))
                throw new IllegalStateException("job type '" + provider.id()
                        + "' requires unavailable Platform Service '" + req + "' (available: "
                        + (platform == null ? Set.of() : platform.ids()) + ")");
        }
        if (providers.putIfAbsent(provider.id(), provider) != null)
            throw new IllegalStateException("duplicate job type id '" + provider.id() + "'");
        owners.put(provider.id(), owner);
        sources.put(provider.id(), source);
    }

    /** One type's provenance, if registered. */
    Optional<Provenance> provenanceOf(String id) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return p == null ? Optional.empty()
                : Optional.of(new Provenance(p.implClass(), sources.get(p.id())));
    }

    /** Remove every type owned by {@code owner} (Job Pack unload/reload); returns the ids removed. */
    List<String> deregister(String owner) {
        List<String> removed = new ArrayList<>();
        for (var it = owners.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (java.util.Objects.equals(owner, e.getValue())) {
                providers.remove(e.getKey());
                sources.remove(e.getKey());
                removed.add(e.getKey());
                it.remove();
            }
        }
        return removed;
    }

    /** Build the {@link Job} for an authored config; throws if the type id is unknown. */
    Job create(String id, JobConfig config) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (p == null)
            throw new IllegalArgumentException("unknown job type '" + id
                    + "' (registered: " + providers.keySet() + ")");
        return p.create(config);
    }

    boolean has(String id) { return id != null && providers.containsKey(id.toLowerCase(Locale.ROOT)); }

    /** The Job Pack that owns {@code id}'s provider, or empty for a built-in/permanent registration
     *  (or an unknown id) — lets a Run pin its owning pack's classloader open for its duration
     *  (Job Pack in-flight-Run quiesce, §12.2). */
    Optional<String> ownerOf(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(owners.get(id.toLowerCase(Locale.ROOT)));
    }

    Set<String> ids() { return Set.copyOf(providers.keySet()); }

    /** Every registered type's descriptor, in registration order (R3 / {@code GET /jobs/types}). */
    List<JobTypeDescriptor> descriptors() {
        return providers.values().stream().map(JobTypeProvider::descriptor).toList();
    }

    /** One type's descriptor by id (case-insensitive), if registered. */
    Optional<JobTypeDescriptor> descriptor(String id) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(p).map(JobTypeProvider::descriptor);
    }

    /** The parameters a type requires for one authored config (R3) — config-aware where the provider
     *  overrides {@link JobTypeProvider#parameters(JobConfig)}; empty for an unknown id. */
    List<ParameterDecl> parameters(String id, JobConfig config) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return p == null ? List.of() : p.parameters(config);
    }
}
