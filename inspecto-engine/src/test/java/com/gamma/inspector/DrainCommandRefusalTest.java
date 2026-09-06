package com.gamma.inspector;

import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PARK-1 (b), 2026-09-06: the expansion refusal names the archive and the sibling Consignments. */
class DrainCommandRefusalTest {

    private static ConsignmentManifest manifest(String batchId, String... originals) {
        ConsignmentManifest m = new ConsignmentManifest();
        m.batchId = batchId;
        m.pipeline = "p";
        m.members = new java.util.ArrayList<>();
        int i = 0;
        for (String orig : originals)
            m.members.add(new ConsignmentManifest.MemberEntry("member" + (i++) + ".csv", i, orig, null, "SUCCESS"));
        return m;
    }

    @Test
    void namesTheArchiveAndEverySiblingHoldingEntriesOfIt(@TempDir Path manifests) throws Exception {
        String dir = manifests.toString();
        ConsignmentManifest mine = manifest("b1", "bundle.zip!good.csv", "plain.csv");
        ManifestStore.write(dir, mine);
        ManifestStore.write(dir, manifest("b2", "bundle.zip!more.csv"));          // sibling: same archive
        ManifestStore.write(dir, manifest("b3", "other.zip!x.csv"));              // a different archive
        ManifestStore.write(dir, manifest("b4", "loose.csv"));                    // no expansion at all

        String msg = DrainCommand.expansionRefusal("b1", mine, dir);
        assertNotNull(msg);
        assertTrue(msg.contains("[bundle.zip]"), msg);
        assertTrue(msg.contains("[b2]"), "only the sibling of the SAME archive is named: " + msg);
        assertFalse(msg.contains("b3") || msg.contains("b4"), msg);
        assertTrue(msg.contains("drain them together"), msg);
        assertTrue(msg.contains("member0.csv") && !msg.contains("member1.csv"),
                "only the expanded member is listed, the plain one is not the problem: " + msg);
    }

    @Test
    void noSiblingsIsSaidPlainlyAndNoExpansionIsNoRefusal(@TempDir Path manifests) throws Exception {
        String dir = manifests.toString();
        ConsignmentManifest lone = manifest("b1", "bundle.zip!good.csv");
        ManifestStore.write(dir, lone);
        String msg = DrainCommand.expansionRefusal("b1", lone, dir);
        assertNotNull(msg);
        assertTrue(msg.contains("No other parked Consignment"), msg);

        assertNull(DrainCommand.expansionRefusal("b9", manifest("b9", "plain.csv"), dir));
        assertNotNull(DrainCommand.expansionRefusal("b1", lone, null), "a missing manifests dir still refuses");
    }
}
