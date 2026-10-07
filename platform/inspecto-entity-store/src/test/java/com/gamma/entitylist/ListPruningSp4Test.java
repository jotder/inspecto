package com.gamma.entitylist;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SP4 correctness (fast): a list member never counts toward degree or budget, whichever SQL form carries the list, over the
 * REAL sidecar written by {@link EntityListSidecar} from the REAL fact log. The cost side is {@link ListPruningSp4Bench}.
 */
class ListPruningSp4Test {

    private static final String EDGES = "e_t";
    private static final String FRONTIER = "fr";

    @Test
    void listMembersNeverCountAndExpiredOrRetiredEntriesDo(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log h = log.read();
        h = log.append(h, "a", "t", "list.created", "excl", Map.of("title", "T", "purpose", "exclude", "entityType", "msisdn"));
        h = log.append(h, "a", "t", "list.member.added", "excl", Map.of("keys", List.of("A", "B", "S2")));
        h = log.append(h, "a", "t", "list.member.added", "excl", Map.of("keys", List.of("EXP"), "expiresAt", "2000-01-01T00:00:00Z"));
        h = log.append(h, "a", "t", "list.created", "bar", Map.of("title", "T", "purpose", "barrier", "entityType", "msisdn"));
        h = log.append(h, "a", "t", "list.member.added", "bar", Map.of("keys", List.of("C")));
        h = log.append(h, "a", "t", "list.created", "old", Map.of("title", "T", "purpose", "exclude", "entityType", "msisdn"));
        h = log.append(h, "a", "t", "list.member.added", "old", Map.of("keys", List.of("RET")));
        h = log.append(h, "a", "t", "list.retired", "old", Map.of());
        h = log.append(h, "a", "t", "list.created", "unused", Map.of("title", "T", "purpose", "exclude", "entityType", "msisdn"));
        h = log.append(h, "a", "t", "list.member.added", "unused", Map.of("keys", List.of("D")));

        Path dataRoot = Files.createDirectories(root.resolve("data"));
        Map<String, EntityRegistry.EntityList> lists = EntityRegistry.fold(h.facts(), h.headSeq());
        for (EntityRegistry.EntityList l : lists.values())
            assertEquals("written", EntityListSidecar.write(dataRoot, l, h.facts()));

        // S1 -> A,B,C,D,EXP,RET,X,X ; S2 (itself a list member) -> X,Y ; S3 is not in the frontier.
        String edges = "(VALUES ('S1','A'),('S1','B'),('S1','C'),('S1','D'),('S1','EXP'),('S1','RET'),('S1','X'),('S1','X'),"
                + "('S2','X'),('S2','Y'),('S3','X')) t(src,dst)";
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataRoot), List.of(dataRoot));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + EDGES + " AS SELECT * FROM " + edges);
            st.execute("CREATE TABLE " + FRONTIER + " AS SELECT * FROM (VALUES ('S1'),('S2')) t(k)");
            String rel = "read_parquet('" + dataRoot.toString().replace('\\', '/') + "/entity_list_*/entries.parquet')";
            st.execute("CREATE TABLE lt AS SELECT * FROM " + rel);
            String ids = "'excl','bar'";
            st.execute("CREATE TABLE list_member AS SELECT DISTINCT entry FROM (" + Sp4Hop.liveKeys("lt", ids) + ")");
            String lits = "'A','B','S2','C'";

            Map<String, Long> base = Sp4Hop.run(c, Sp4Hop.sql(Sp4Hop.Form.NONE, EDGES, FRONTIER, null, null, null));
            assertEquals(3L, base.get("X"), "baseline: S1 x2 + S2 x1");

            // expected: S2 dropped from the frontier (a member); A,B,C dropped; EXP (expired), RET (retired list) and
            // D (list not asked for) still COUNT.
            Map<String, Long> expect = Map.of("D", 1L, "EXP", 1L, "RET", 1L, "X", 2L);
            Map<String, Long> viaSidecar = Sp4Hop.run(c, Sp4Hop.sql(Sp4Hop.Form.SIDECAR, EDGES, FRONTIER, rel, ids, null));
            assertEquals(expect, viaSidecar);
            assertEquals(expect, Sp4Hop.run(c, Sp4Hop.sql(Sp4Hop.Form.IN_LIST, EDGES, FRONTIER, null, ids, lits)));
            assertEquals(expect, Sp4Hop.run(c, Sp4Hop.sql(Sp4Hop.Form.LIST_TABLE, EDGES, FRONTIER, "lt", ids, null)));
            assertEquals(expect, Sp4Hop.run(c, Sp4Hop.sql(Sp4Hop.Form.LIST_MEMBER, EDGES, FRONTIER, "list_member", ids, null)));
            assertFalse(viaSidecar.containsKey("Y"), "a dropped frontier node spends no budget");
            assertEquals(5L, viaSidecar.values().stream().mapToLong(Long::longValue).sum(), "budget = surviving edges");

            // whole-relation degree: members are never counted
            Map<String, Long> deg = Sp4Hop.run(c, Sp4Hop.degreeSql(Sp4Hop.Form.SIDECAR, EDGES, rel, ids, null));
            assertFalse(deg.containsKey("A") || deg.containsKey("B") || deg.containsKey("C"));
            assertEquals(4L, deg.get("X"));
            assertEquals(deg, Sp4Hop.run(c, Sp4Hop.degreeSql(Sp4Hop.Form.LIST_MEMBER, EDGES, "list_member", ids, null)));
        }
    }
}
