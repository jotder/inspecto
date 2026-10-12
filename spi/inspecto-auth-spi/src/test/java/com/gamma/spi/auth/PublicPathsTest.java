package com.gamma.spi.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPathsTest {

    private static final String PREFIX = "/public/test-embed/";

    @Test
    void aRegisteredPrefixIsSelfVerifyingAndItsCredentialIsRedacted() {
        PublicPaths.registerSelfVerifying(PREFIX);
        PublicPaths.registerSelfVerifying(PREFIX);   // idempotent
        assertTrue(PublicPaths.isSelfVerifying(PREFIX + "abc.def"));
        assertFalse(PublicPaths.isSelfVerifying("/public/other/abc"), "only a registered prefix is exempt");
        assertFalse(PublicPaths.isSelfVerifying("/inv/investigations/x"));
        assertFalse(PublicPaths.isSelfVerifying(null));

        assertEquals(PREFIX + PublicPaths.REDACTED, PublicPaths.redact(PREFIX + "abc.def"));
        assertEquals("/api/v1" + PREFIX + PublicPaths.REDACTED, PublicPaths.redact("/api/v1" + PREFIX + "abc.def"), "after the API prefix too");
        assertEquals("/api/v1/spaces/acme" + PREFIX + PublicPaths.REDACTED, PublicPaths.redact("/api/v1/spaces/acme" + PREFIX + "abc.def"));
        assertEquals(PREFIX + PublicPaths.REDACTED, PublicPaths.redact(PREFIX + "abc/def/ghi"), "everything after the prefix, not just a segment");
        assertEquals("/inv/investigations/x", PublicPaths.redact("/inv/investigations/x"), "any other path is untouched");
        assertEquals(null, PublicPaths.redact(null));
    }

    @Test
    void aPrefixIsPublicSlashNameSlashSoNothingBroadCanBeRegistered() {
        for (String bad : new String[] {null, "", "public/x/", "/public/x", "/", "/inv/x/", "/public/"})
            assertThrows(IllegalArgumentException.class, () -> PublicPaths.registerSelfVerifying(bad), String.valueOf(bad));
    }
}
