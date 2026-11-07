package com.gamma.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-SHOWCASE-DIST-1: a Space naming a newer {@code min_product_version} than the running product is flagged. */
class SpaceVersionCheckTest {

    @TempDir Path tmp;

    @Test
    void olderProductIsFlagged() {
        assertTrue(SpaceVersionCheck.tooOld("4.0.0", "4.1.0"));
        assertTrue(SpaceVersionCheck.tooOld("4.0.0-SNAPSHOT", "4.0.1"));
        assertTrue(SpaceVersionCheck.tooOld("3.9", "4.0.0"));
    }

    @Test
    void sameOrNewerProductIsNotFlagged() {
        assertFalse(SpaceVersionCheck.tooOld("4.0.0", "4.0.0"));
        assertFalse(SpaceVersionCheck.tooOld("4.0.0-SNAPSHOT", "4.0"));
        assertFalse(SpaceVersionCheck.tooOld("4.2.0", "4.1.9"));
    }

    @Test
    void devRunAndNoRequirementCheckNothing() {
        assertFalse(SpaceVersionCheck.tooOld(ProductVersion.DEV, "99.0.0"));
        assertFalse(SpaceVersionCheck.tooOld("4.0.0", ""));
    }

    @Test
    void requiredIsReadFromSpaceToon() throws Exception {
        Files.writeString(tmp.resolve("space.toon"), "display_name: X\nmin_product_version: \"4.1.0\"\n");
        assertEquals("4.1.0", SpaceVersionCheck.required(tmp));
    }

    @Test
    void missingManifestRequiresNothing() {
        assertEquals("", SpaceVersionCheck.required(tmp));
    }
}
