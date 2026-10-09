package com.gamma.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REACTOR-ROOT-WORKTREE-1: a worktree nested inside another checkout resolves to ITSELF, not the ancestor reactor. */
class ReactorModulesRootTest {

    private static void reactor(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("mod"));
        Files.writeString(dir.resolve("pom.xml"), "<project><modules><module>mod</module></modules></project>");
    }

    @Test
    void nestedWorktreeResolvesToItselfNotTheEnclosingCheckout(@TempDir Path tmp) throws Exception {
        reactor(tmp);
        Files.createDirectories(tmp.resolve(".git"));
        Path lane = tmp.resolve(".claude/worktrees/lane");
        reactor(lane);
        Files.writeString(lane.resolve(".git"), "gitdir: elsewhere"); // a linked worktree has a .git FILE
        assertEquals(lane.toRealPath(), ReactorModules.root(lane.resolve("mod")).toRealPath());
    }

    @Test
    void checkoutWithoutNestingStillResolvesToItsOutermostPom(@TempDir Path tmp) throws Exception {
        reactor(tmp);
        Files.createDirectories(tmp.resolve(".git"));
        assertEquals(tmp.toRealPath(), ReactorModules.root(tmp.resolve("mod")).toRealPath());
    }
}
