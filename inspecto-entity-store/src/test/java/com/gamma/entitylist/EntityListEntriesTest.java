package com.gamma.entitylist;

import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSURE-ENTITY-LISTS-1 (WS-12): range / CIDR entries, expiry and the Parquet sidecar, below HTTP. The properties:
 * a block admits exactly its addresses (IPv4 and IPv6 edges, the IPv4-mapped spelling), an expired entry stops
 * matching while the as-of fold still shows it, and the sidecar reads as a Dataset through the production resolver.
 */
class EntityListEntriesTest {

    private static EntityListEntries.Range cidr(String c) {
        return EntityListEntries.parse(EntityListEntries.canonical(Map.of("cidr", c), "default"));
    }

    private static boolean in(EntityListEntries.Range r, String ip) {
        return EntityListEntries.matches(r, ip, ip);
    }

    @Test
    void anIpv4BlockAdmitsExactlyItsAddresses() {
        EntityListEntries.Range r = cidr("10.1.0.0/16");
        assertEquals("cidr:10.1.0.0/16", r.canonical());
        assertTrue(in(r, "10.1.0.0"), "the network address");
        assertTrue(in(r, "10.1.255.255"), "the last address");
        assertFalse(in(r, "10.2.0.0"), "one past the end");
        assertFalse(in(r, "10.0.255.255"), "one before the start");
        assertTrue(in(r, " 10.1.2.3 "), "trimmed");
        assertFalse(in(r, "10.1.2"), "not an address");
        assertFalse(in(r, "010.1.2.3"), "leading zeros are refused, never read as octal or decimal");
        assertFalse(in(r, "10.1.2.256"));
        assertFalse(in(r, "host.example"), "never resolved as a name");

        assertTrue(in(cidr("0.0.0.0/0"), "255.255.255.255"), "/0 admits every IPv4 address");
        assertFalse(in(cidr("0.0.0.0/0"), "::1"), "an IPv4 block admits no IPv6 address");
        EntityListEntries.Range host = cidr("192.0.2.7/32");
        assertTrue(in(host, "192.0.2.7"));
        assertFalse(in(host, "192.0.2.6"));
        assertFalse(in(host, "192.0.2.8"));
    }

    @Test
    void anIpv6BlockAdmitsExactlyItsAddressesInEverySpelling() {
        EntityListEntries.Range r = cidr("2001:DB8::/32");
        assertEquals("cidr:2001:db8:0:0:0:0:0:0/32", r.canonical(), "one canonical spelling, whatever was typed");
        assertTrue(in(r, "2001:db8::"));
        assertTrue(in(r, "2001:0db8:ffff:ffff:ffff:ffff:ffff:ffff"), "the last address");
        assertTrue(in(r, "2001:DB8:0:0:1::1"), "upper case, compressed");
        assertFalse(in(r, "2001:db9::"), "one past the end");
        assertFalse(in(r, "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff"), "one before the start");
        assertFalse(in(r, "10.0.0.1"), "an IPv6 block admits no IPv4 address");
        assertFalse(in(r, "2001:db8::1%eth0"), "a zone id is not an address");
        assertFalse(in(r, "2001:db8::1::2"), "two '::' are malformed");
        assertFalse(in(r, "2001:db8:1:2:3:4:5:6:7"), "nine groups are malformed");
        assertTrue(in(cidr("::/0"), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), "/0 admits every IPv6 address");
        EntityListEntries.Range host = cidr("::1/128");
        assertTrue(in(host, "0:0:0:0:0:0:0:1"));
        assertFalse(in(host, "::2"));
        assertTrue(in(cidr("2001:db8::/126"), "2001:db8::3"), "the last of a /126");
        assertFalse(in(cidr("2001:db8::/126"), "2001:db8::4"));
    }

    @Test
    void anIpv4MappedAddressIsTheIpv4AddressBothWays() {
        assertEquals("cidr:192.0.2.0/24", cidr("::ffff:192.0.2.0/120").canonical(), "a mapped block is an IPv4 block");
        EntityListEntries.Range v4 = cidr("192.0.2.0/24");
        assertTrue(in(v4, "::ffff:192.0.2.9"), "a mapped candidate matches the IPv4 block");
        assertTrue(in(v4, "::FFFF:c000:0209"), "in hex too");
        assertFalse(in(v4, "::ffff:192.0.3.1"));
        assertThrows(IllegalArgumentException.class, () -> cidr("::ffff:192.0.2.0/64"), "a mapped block shorter than /96");
    }

    @Test
    void aBlockWithHostBitsOrABadLengthIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> cidr("10.1.2.3/16"));
        assertTrue(e.getMessage().contains("10.1.0.0/16"), "names the network: " + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> cidr("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> cidr("2001:db8::/129"));
        assertThrows(IllegalArgumentException.class, () -> cidr("10.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> cidr("10.0.0.0/-1"));
        assertThrows(IllegalArgumentException.class, () -> cidr("10.0.0.0/8/8"));
        assertThrows(IllegalArgumentException.class, () -> cidr("example.com/8"));
    }

    @Test
    void aPrefixAndASameLengthRangeMatchUnderTheListsNormaliser() {
        EntityListEntries.Range p = EntityListEntries.parse(EntityListEntries.canonical(Map.of("prefix", "+44 78"), "e164"));
        assertEquals("prefix:+4478", p.canonical());
        assertTrue(EntityListEntries.matches(p, "+447800900123", null));
        assertFalse(EntityListEntries.matches(p, "+447900900123", null));
        assertFalse(EntityListEntries.matches(p, "", null));

        EntityListEntries.Range r = EntityListEntries.parse(EntityListEntries.canonical(
                Map.of("from", "447800000000", "to", "447899999999"), "digits"));
        assertTrue(EntityListEntries.matches(r, "447800000000", null), "the low bound");
        assertTrue(EntityListEntries.matches(r, "447899999999", null), "the high bound");
        assertFalse(EntityListEntries.matches(r, "447900000000", null), "one past the end");
        assertFalse(EntityListEntries.matches(r, "44785", null), "sorts inside, but is not the same length");
        assertFalse(EntityListEntries.matches(r, "4478000000001", null), "longer");
        assertThrows(IllegalArgumentException.class, () -> EntityListEntries.canonical(Map.of("from", "9", "to", "10"), "digits"));
        assertThrows(IllegalArgumentException.class, () -> EntityListEntries.canonical(Map.of("from", "20", "to", "10"), "digits"));
        assertThrows(IllegalArgumentException.class, () -> EntityListEntries.canonical(Map.of("prefix", "a", "cidr", "x"), "digits"));
        assertThrows(IllegalArgumentException.class, () -> EntityListEntries.canonical(Map.of("prefix", "--"), "digits"));
    }

    // ── expiry in the fold ─────────────────────────────────────────────────────────────────────────────

    private static EntityFactLog.Log list(EntityFactLog log, String id, String purpose, String type, String normaliser)
            throws Exception {
        return log.append(log.read(), "a1", "open", "list.created", id,
                Map.of("title", "T", "purpose", purpose, "entityType", type, "normaliser", normaliser));
    }

    @Test
    void anExpiredEntryStaysInTheFoldButStopsMatching(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log head = list(log, "bl", "block", "msisdn", "e164");
        Instant now = Instant.now();
        String past = now.minusSeconds(60).toString(), future = now.plusSeconds(3600).toString();
        head = log.append(head, "a1", "add", "list.member.added", "bl", Map.of("keys", List.of("+441"), "expiresAt", past));
        head = log.append(head, "a1", "add", "list.member.added", "bl", Map.of("keys", List.of("+442"), "expiresAt", future));
        head = log.append(head, "a1", "add", "list.member.added", "bl", Map.of("keys", List.of("+443")));
        head = log.append(head, "a1", "add", "list.range.added", "bl", Map.of("ranges", List.of("prefix:+449"), "expiresAt", past));
        EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get("bl");
        assertEquals(List.of("+441", "+442", "+443"), List.copyOf(l.members()), "the fold keeps an expired key");
        assertEquals(List.of("+442", "+443"), List.copyOf(l.liveMembers(now)));
        assertNull(l.match("+441", now), "an expired key does not match");
        assertEquals("+442", l.match("+442", now), "a key that has not expired yet matches");
        assertEquals("+443", l.match("0044 3", now).replace(" ", ""), "normalised by the sealed normaliser");
        assertNull(l.match("+449123", now), "an expired range does not match");
        assertEquals("+441", EntityRegistry.fold(head.facts(), head.headSeq()).get("bl").match("+441", now.minusSeconds(120)),
                "before its expiry it matched");

        head = log.append(head, "a1", "add", "list.member.added", "bl", Map.of("keys", List.of("+441")));
        assertEquals("+441", EntityRegistry.fold(head.facts(), head.headSeq()).get("bl").match("+441", now),
                "a later permanent add revives it");
        head = log.append(head, "a1", "drop", "list.member.removed", "bl", Map.of("keys", List.of("+442")));
        assertFalse(EntityRegistry.fold(head.facts(), head.headSeq()).get("bl").expiresAt().containsKey("+442"),
                "a remove forgets the expiry");
    }

    // ── the sidecar ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theSidecarReadsAsADatasetAndJoinsByRangeInSql(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log head = list(log, "ip-block", "block", "handset", "default");
        String past = Instant.now().minusSeconds(60).toString();
        head = log.append(head, "a1", "fraud ring", "list.range.added", "ip-block",
                Map.of("ranges", List.of("cidr:10.1.0.0/16", "cidr:2001:db8:0:0:0:0:0:0/32")));
        head = log.append(head, "a2", "stale", "list.range.added", "ip-block",
                Map.of("ranges", List.of("cidr:192.0.2.0/24"), "expiresAt", past));
        head = log.append(head, "a1", "one", "list.member.added", "ip-block", Map.of("keys", List.of("h-1")));
        EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get("ip-block");

        assertEquals("written", EntityListSidecar.write(data, l, head.facts()));
        String rel = "(" + DatasetRelation.relationSql(Map.of("physicalRef", "entity_list_ip-block"), data, null) + ")";
        DuckDbUtil.loadDriver();
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(data)); Statement st = c.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT match, entry, added_by, reason, expires_at IS NOT NULL FROM " + rel
                    + " ORDER BY entry")) {
                while (rs.next()) rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getBoolean(5));
            }
            assertEquals(List.of("cidr|cidr:10.1.0.0/16|a1|fraud ring|false",
                    "cidr|cidr:192.0.2.0/24|a2|stale|true",
                    "cidr|cidr:2001:db8:0:0:0:0:0:0/32|a1|fraud ring|false",
                    "key|h-1|a1|one|false"), rows, "one row per entry, with who added it and why");
            // A range join the equal-key join cannot express: an address as hex BETWEEN lo AND hi, live entries only.
            String hex = "lower(printf('%02x%02x%02x%02x', 10, 1, 7, 9))";
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + rel + " WHERE match = 'cidr' AND length(lo) = 8 AND "
                    + hex + " BETWEEN lo AND hi AND (expires_at IS NULL OR expires_at > now())")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "10.1.7.9 is inside 10.1.0.0/16");
            }
            String expired = "lower(printf('%02x%02x%02x%02x', 192, 0, 2, 5))";
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + rel + " WHERE match = 'cidr' AND length(lo) = 8 AND "
                    + expired + " BETWEEN lo AND hi AND (expires_at IS NULL OR expires_at > now())")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the expired block no longer matches");
            }
        }

        head = log.append(head, "a1", "done", "list.retired", "ip-block", Map.of());
        assertEquals("written", EntityListSidecar.write(data, EntityRegistry.fold(head.facts(), head.headSeq()).get("ip-block"),
                head.facts()));
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(data)); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + rel)) {
            rs.next();
            assertEquals(0, rs.getInt(1), "a retired list writes no rows");
        }
    }

    @Test
    void theSidecarNeverWritesIntoADirectoryItDidNotCreate(@TempDir Path root, @TempDir Path data) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log head = list(log, "orders", "watch", "handset", "default");
        Path foreign = Files.createDirectories(data.resolve("entity_list_orders"));
        Files.writeString(foreign.resolve("entries.parquet"), "not ours");
        assertEquals("failed", EntityListSidecar.write(data, EntityRegistry.fold(head.facts(), 1).get("orders"), head.facts()));
        assertEquals("not ours", Files.readString(foreign.resolve("entries.parquet")), "untouched");
        assertEquals("none", EntityListSidecar.write(null, EntityRegistry.fold(head.facts(), 1).get("orders"), head.facts()));
    }
}
