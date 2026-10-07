package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.util.RunLog;

/**
 * A Job Pack's contribution of a Platform Service (platform-services Stage 3, S3-1): the pack-side
 * counterpart of the boot-time {@code PlatformServiceRegistry.register}. Discovered through
 * {@code META-INF/services} like every other pack provider and bound under the pack's owner key, so an
 * unload takes the binding back with the pack.
 *
 * <p><b>The interface is engine-published (D-12, 2026-10-04).</b> {@link #type()} must be an interface the
 * ENGINE classpath already exposes (an {@code @PublicApi} type), never one the pack defines: a consumer in
 * another pack resolves the service by {@code Class} identity, and two pack loaders cannot share a type.
 * A pack therefore supplies an implementation of an interface that exists but is not yet bound in this
 * build. A pack-defined interface (a shared pack-API loader) is a later design.
 *
 * <p>Fail-closed: a colliding {@link #id()} or {@link #type()} rejects the whole pack, and so does a
 * mutating service with no usable dry-run stand-in.
 */
@PublicApi(since = "4.0.0")
public interface ServiceProvider {

    /** The id consumers declare in {@code requires:}. */
    String id();

    /** The engine-published interface the service implements. */
    Class<?> type();

    /** Build the service. Called once, when the pack loads. */
    Object create();

    /** {@code true} when the service never mutates anything, so a dry run passes it through unchanged. */
    default boolean readOnly() {
        return false;
    }

    /**
     * The dry-run stand-in of a <em>mutating</em> service: an instance of {@link #type()} that logs the
     * would-be effect to {@code log} and performs nothing. Mandatory unless {@link #readOnly()} — without
     * it a dry run would call the real service (the MNT-1 violation).
     */
    default Object dryRun(RunLog log) {
        return null;
    }
}
