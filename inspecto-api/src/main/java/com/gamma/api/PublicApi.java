package com.gamma.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, or constructor as part of the <b>stable public API</b> —
 * the surface external code (plugin authors implementing {@code StreamingFileIngester},
 * and embedders driving the ETL from Java) is allowed to depend on.
 *
 * <h3>Stability contract</h3>
 * Within a major version, {@code @PublicApi} elements evolve under semantic
 * versioning: they are not removed or changed incompatibly; new members may be
 * added. Breaking changes to them happen only on a major version bump (and are
 * called out in the release notes).
 *
 * <p>Anything <em>not</em> annotated is internal: it may change or disappear in
 * any release, with no notice. Do not depend on unmarked types from outside the
 * framework. See {@code docs/okf/backend/control-plane/api-stability.md} for the
 * full policy and the current marked surface.
 *
 * <p><b>The promise binds only within a <em>released</em> major.</b> The newest
 * release on this line is {@code v3.11.0}; the trunk is {@code 4.0.0-SNAPSHOT}
 * and 4.0.0 has not shipped. An element marked {@code since = "4.0.0"} has
 * therefore never been published, and may still be moved, renamed or changed —
 * this marker alone does not make such a change breaking.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS} — visible in bytecode for tooling
 * and Javadoc, but not required at runtime.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface PublicApi {

    /**
     * Version in which this element became public API (e.g. {@code "2.0.0"}). Informational.
     *
     * <p>A value of {@code "4.0.0"} means <em>will become</em> public API in the pending 4.0.0 —
     * nothing after 3.x has been released. Do not write a value above {@code 4.0.0}: versions
     * beyond the pending major do not exist, and 200 sites once claimed 4.1.0-5.8.0 by mistake.
     */
    String since() default "";
}
