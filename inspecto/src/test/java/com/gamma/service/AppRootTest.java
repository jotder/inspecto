package com.gamma.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPACE-RELOCATABLE-ROOTS-1: the install root, and the {@code spaces.ref} pointer that hangs off it. */
class AppRootTest {

    @TempDir Path tmp;

    @AfterEach
    void clearProperty() {
        System.clearProperty(AppRoot.PROPERTY);
    }

    @Test
    void unsetIsTheWorkingDirectory() {
        assertEquals(Path.of("").toAbsolutePath().normalize(), AppRoot.current());
    }

    @Test
    void theFlagWinsAndIsNormalized() {
        System.setProperty(AppRoot.PROPERTY, tmp.resolve("a").resolve("..").resolve("b").toString());
        assertEquals(tmp.resolve("b"), AppRoot.current());
    }

    @Test
    void anAbsolutePathIsLeftAloneAndARelativeOneHangsOffTheRoot() {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        assertEquals(tmp.resolve("la-data").resolve("demo"), AppRoot.resolve("la-data/demo"));
        assertEquals(tmp.resolve("absolute"), AppRoot.resolve(tmp.resolve("absolute").toString()));
    }

    @Test
    void noPointerMeansSpacesUnderTheAppRoot() {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        assertEquals(tmp.resolve("spaces"), SpacesPointer.resolve());
    }

    @Test
    void aPointerNamesTheSpacesRootAbsoluteOrRelative() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere").resolve("spaces"));
        Files.writeString(tmp.resolve(SpacesPointer.FILE), "elsewhere/spaces\n");
        assertEquals(elsewhere, SpacesPointer.resolve());
        Files.writeString(tmp.resolve(SpacesPointer.FILE), elsewhere + "\n");
        assertEquals(elsewhere, SpacesPointer.resolve());
    }

    /** A pointer the operator wrote and got wrong must not read as "no Spaces configured". */
    @Test
    void anUnusablePointerIsALoudFailureNotAFallThrough() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Files.createDirectories(tmp.resolve("spaces"));   // the default exists, so a fall-through would boot quietly
        Files.writeString(tmp.resolve(SpacesPointer.FILE), "   \n");
        assertTrue(assertThrows(IllegalStateException.class, SpacesPointer::resolve).getMessage().contains("is empty"));
        Files.writeString(tmp.resolve(SpacesPointer.FILE), "no/such/dir\n");
        assertTrue(assertThrows(IllegalStateException.class, SpacesPointer::resolve)
                .getMessage().contains("is not a directory"));
    }
}
