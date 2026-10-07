package com.gamma.spi.auth;

import com.gamma.api.PublicApi;

import java.io.IOException;

/**
 * Answers "is this user id a real identity?" — the one question the platform needs of the IAM that owns its
 * identities (ASSURE-WORKFLOW-SLA-1: an Escalation Rule's {@code reassign} is checked against it when the rule is
 * saved). An edition seam like {@link TokenRelay}: an implementation arrives via
 * {@code META-INF/services/com.gamma.spi.auth.PrincipalDirectory}; the core ships none.
 *
 * <p><b>Offline editions have no directory.</b> Personal and Standard (and the demo build) run without an IAM, so
 * nothing registers an implementation and the check degrades to the id-shape check alone — the same standing as
 * {@code POST /objects/{id}/assign}. A registered directory is authoritative and fails CLOSED: a user it does not
 * know, or a lookup that throws, refuses the save.
 */
@PublicApi(since = "4.0.0")
public interface PrincipalDirectory {

    /** {@code true} when {@code userId} is a known identity. Throw {@link IOException} when the IAM cannot answer. */
    boolean exists(String userId) throws IOException;
}
