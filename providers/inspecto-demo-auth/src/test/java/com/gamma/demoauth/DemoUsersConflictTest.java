package com.gamma.demoauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** LIVEFIX2 #3: a Demo User defined differently in two Spaces is refused with BOTH Spaces named. */
class DemoUsersConflictTest {

    @Test
    void theConflictNamesTheUserAndBothSpaces(@TempDir Path root) throws Exception {
        Path a = Files.createDirectories(root.resolve("acme").resolve("config"));
        Path b = Files.createDirectories(root.resolve("telco-ra").resolve("config"));
        Files.writeString(a.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,admin\n");
        Files.writeString(b.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,operations\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DemoUsers.all(root));
        assertTrue(e.getMessage().contains("'admin'"), e.getMessage());
        assertTrue(e.getMessage().contains("'acme'") && e.getMessage().contains("'telco-ra'"), e.getMessage());
    }
}
