package com.gamma.spi.auth;

import java.time.Instant;

/**
 * The principal behind a read-only embedded Investigation link (LA-EMBED-VIEW-1, design
 * {@code la-embed-view-design.md} section 3). A deliberately powerless record: it is <b>not</b> a {@link Subject} and not a
 * subtype of one, carries no capability and no data scope, and is attached to an exchange only by the public embed route
 * (as {@link RequestAttrs#ATTR_EMBED_GRANT}) after a token lookup succeeded. Because no {@code Subject} is ever attached,
 * every capability gate ({@code ApiContext.withCapability}) and every policy check fails closed for its holder; the holder can
 * reach ONLY the one public route that resolved it - a structural property, not a list of denials.
 *
 * <p>The audit actor of a use is {@link #actor()} ({@code embed:<grantId>}); {@link #issuedBy()} is a field on the row.
 *
 * @param grantId         the grant's id (also the public part of its token)
 * @param investigationId the ONE Investigation it shows
 * @param issuedBy        the Subject that minted it
 * @param issuedAt        when it was minted
 * @param expiresAt       when it stops working (mandatory)
 * @param label           the minter's free-text note, or null
 */
public record EmbedGrant(String grantId, String investigationId, String issuedBy, Instant issuedAt, Instant expiresAt,
                         String label) {

    /** The audit actor of a use of this grant. */
    public String actor() {
        return "embed:" + grantId;
    }
}
