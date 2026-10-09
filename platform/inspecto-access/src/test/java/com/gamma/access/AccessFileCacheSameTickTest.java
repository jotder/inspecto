package com.gamma.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every access file cache must see a rewrite that keeps the SAME size and lands in the SAME mtime tick (a fast
 * PUT-then-hand-edit, or any rewrite on a coarse-timestamp filesystem). Keyed on (mtime ms, size), each of these
 * caches went on serving the old file — for an access file that is fail-open: a tightened grant ignored until the
 * mtime moved. The Safety Policy cache had the same flaw ({@code SafetyPolicyFilesTest}, Linux CI).
 */
class AccessFileCacheSameTickTest {

    /** Rewrite {@code file} with {@code text} (same size as before) and pin its mtime back to the old tick. */
    private static void rewriteInSameTick(Path file, String text) throws Exception {
        FileTime tick = Files.getLastModifiedTime(file);
        assertEquals(Files.size(file), text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, "same size");
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, tick);
    }

    @Test
    void rolesSeesASameSizeSameTickRewrite(@TempDir Path root) throws Exception {
        Roles.write(root, Map.of(), List.of("dept"));
        assertEquals(List.of("dept"), Roles.load(root).attributeClaims());
        Path file = root.resolve(Roles.FILE);
        rewriteInSameTick(file, Files.readString(file).replace("dept", "unit"));
        assertEquals(List.of("unit"), Roles.load(root).attributeClaims());
    }

    @Test
    void accessPoliciesSeeASameSizeSameTickRewrite(@TempDir Path root) throws Exception {
        Path file = root.resolve(AccessPolicyStore.FILE);
        Files.writeString(file, "policies[0]:\n");
        assertFalse(AccessPolicyStore.load(root).unreadable());
        rewriteInSameTick(file, "policies[1]:\n");   // a header promising a policy that is not there: unreadable
        assertTrue(AccessPolicyStore.load(root).unreadable(), "the broken file must deny (fail-closed), not serve the old one");
    }

    @Test
    void roleProfilesSeeASameSizeSameTickRewrite(@TempDir Path root) throws Exception {
        Path registry = root.resolve("registry");
        Files.createDirectories(registry.resolve("access-catalog"));
        Files.createDirectories(registry.resolve("access-profiles"));
        Files.writeString(registry.resolve("access-catalog/catalog.toon"), "nodes[1]{id,capability}:\n  n1,canAdminister\n");
        Path profile = registry.resolve("access-profiles/role-ops.toon");
        Files.writeString(profile, "grants:\n  n1: allow\n");
        assertEquals(Set.of(), AccessGrants.deniedCapabilities(root, List.of("ops")));
        rewriteInSameTick(profile, "grants:\n  n1: deny \n");
        assertEquals(Set.of("canAdminister"), AccessGrants.deniedCapabilities(root, List.of("ops")),
                "a tightened profile must take effect at once");
    }
}
