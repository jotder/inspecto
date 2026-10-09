package com.gamma.ops;

import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A same-size rewrite of an SLA policy inside one mtime tick must be re-read: keyed on (mtime ms, size) the registry
 * kept serving the OLD policy, so changed deadlines and escalations never applied.
 */
class GovernanceRegistrySameTickTest {

    private static String policy(int resolutionMinutes) {
        return "objectType: INCIDENT\ncalendar:\n  zone: Etc/UTC\n  start: \"00:00\"\n"
                + "targets[1]{priority,responseMinutes,resolutionMinutes}:\n  CRITICAL,30," + resolutionMinutes + "\n";
    }

    @Test
    void aSameSizeSameTickRewriteIsReRead(@TempDir Path registry) throws Exception {
        Path f = Files.createDirectories(registry.resolve("sla-policies")).resolve("incident.toon");
        Files.writeString(f, policy(120));
        GovernanceRegistry reg = new GovernanceRegistry(registry);
        Object before = reg.snapshot().slaPolicies().get(ObjectType.INCIDENT);
        assertNotNull(before, "the policy parses");
        FileTime tick = Files.getLastModifiedTime(f);
        Files.writeString(f, policy(480));
        Files.setLastModifiedTime(f, tick);
        Object after = reg.snapshot().slaPolicies().get(ObjectType.INCIDENT);
        assertNotSame(before, after, "the rewritten policy must be re-read, not served from the cache");
    }
}
