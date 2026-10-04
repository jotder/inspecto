package com.gamma.entitylist;

import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * The Parquet sidecar of an Entity List (ASSURE-ENTITY-LISTS-1, WS-12): a derived, rewritten-on-change copy of the
 * list's entries at {@code <dataRoot>/entity_list_<id>/entries.parquet}. A Dataset with
 * {@code physicalRef: entity_list_<id>} reads it, so a 10⁵-entry list joins in SQL like any other relation.
 *
 * <p>Columns: {@code list_id, purpose, entity_type, match (key | prefix | range | cidr), entry, lo, hi, expires_at,
 * added_at, added_by, reason} (the fact that last added the entry). For {@code key} lo = hi = the key; {@code prefix} lo = hi = the prefix ({@code starts_with});
 * {@code range} a same-length key {@code BETWEEN lo AND hi}; {@code cidr} lo / hi are the block's first and last address
 * as fixed-width lower-case hex (8 digits for IPv4, 32 for IPv6). Expired entries are KEPT with their
 * {@code expires_at}, because time passes without a write: a consumer filters
 * {@code expires_at IS NULL OR expires_at > now()}. A retired list writes no rows.
 *
 * <p>⚠ The Identity Fact log is the truth; this file is a projection. A failed write never undoes the fact: the
 * route answers {@code sidecar: "failed"} and logs it. The writer only ever touches a directory carrying its own
 * {@code .entity-list-output} marker naming the list, so it can never overwrite a Dataset it did not create.
 */
final class EntityListSidecar {

    private static final Logger log = LoggerFactory.getLogger(EntityListSidecar.class);
    static final String PREFIX = "entity_list_";
    static final String FILE = "entries.parquet";
    static final String MARKER = ".entity-list-output";

    private EntityListSidecar() {}

    /** The store name a Dataset's {@code physicalRef} uses to read {@code listId}'s sidecar. */
    static String ref(String listId) {
        return PREFIX + listId;
    }

    /** Whether {@code listId}'s sidecar is on disk (written by this class: marker + file), so a Dataset over it can read. */
    static boolean present(Path dataRoot, String listId) {
        if (dataRoot == null) return false;
        Path dir = dataRoot.resolve(ref(listId)).normalize();
        return dir.startsWith(dataRoot.normalize()) && Files.isRegularFile(dir.resolve(MARKER))
                && Files.isRegularFile(dir.resolve(FILE));
    }

    /** Rewrite {@code l}'s sidecar; answers {@code written}, {@code none} (no data root, or none on disk) or {@code failed}. */
    static String write(Path dataRoot, EntityRegistry.EntityList l, java.util.List<EntityFactLog.Fact> facts) {
        // A data root that does not exist is not created here: the legacy single-Space default is relative to the
        // CWD, and a derived projection must never be the thing that plants a data tree there.
        if (dataRoot == null || !Files.isDirectory(dataRoot)) return "none";
        try {
            Path dir = dataRoot.resolve(PREFIX + l.id()).normalize();
            if (!dir.startsWith(dataRoot.normalize())) throw new IllegalStateException("sidecar escapes the data root");
            claim(dir, l.id());
            Path tmp = dir.resolve(FILE + ".tmp");
            try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataRoot), java.util.List.of(dir));
                 Statement st = c.createStatement()) {
                st.execute("CREATE TABLE e (list_id VARCHAR, purpose VARCHAR, entity_type VARCHAR, match VARCHAR, "
                        + "entry VARCHAR, lo VARCHAR, hi VARCHAR, expires_at TIMESTAMP, added_at TIMESTAMP, added_by VARCHAR, reason VARCHAR)");
                java.util.Map<String, java.util.Map<String, Object>> keyBy = new java.util.HashMap<>(), rangeBy = new java.util.HashMap<>();
                for (EntityFactLog.Fact f : facts) {
                    if (f.seq() > l.lastSeq() || !l.id().equals(f.listId())) continue;
                    if ("list.member.added".equals(f.kind()))
                        EntityRegistry.strings(f.body().get("keys")).forEach(k -> keyBy.put(k, f.body()));
                    if ("list.range.added".equals(f.kind()))
                        EntityRegistry.strings(f.body().get("ranges")).forEach(k -> rangeBy.put(k, f.body()));
                }
                if (!l.retired()) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO e VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        for (String k : l.members()) row(ps, l, "key", k, k, k, l.expiresAt().get(k), keyBy.get(k));
                        for (String r : l.ranges()) {
                            EntityListEntries.Range range = EntityListEntries.parse(r);
                            row(ps, l, range.match(), r, range.lo(), range.hi(), l.rangeExpiresAt().get(r), rangeBy.get(r));
                        }
                        ps.executeBatch();
                    }
                }
                st.execute("COPY e TO '" + tmp.toString().replace('\\', '/').replace("'", "''") + "' (FORMAT PARQUET)");
            }
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return "written";
        } catch (IOException | SQLException | RuntimeException e) {
            log.warn("[ENTITY-LIST] sidecar for '{}' not written ({})", l.id(), e.getClass().getSimpleName());
            return "failed";
        }
    }

    private static void row(PreparedStatement ps, EntityRegistry.EntityList l, String match, String entry, String lo,
                            String hi, String expiresAt, java.util.Map<String, Object> addedBy) throws SQLException {
        ps.setString(1, l.id());
        ps.setString(2, l.purpose());
        ps.setString(3, l.entityType());
        ps.setString(4, match);
        ps.setString(5, entry);
        ps.setString(6, lo);
        ps.setString(7, hi);
        ps.setTimestamp(8, expiresAt == null ? null : Timestamp.from(Instant.parse(expiresAt)));
        Object at = addedBy == null ? null : addedBy.get("at");
        ps.setTimestamp(9, at == null ? null : Timestamp.from(Instant.parse(String.valueOf(at))));
        ps.setString(10, addedBy == null ? null : (String) addedBy.get("actor"));
        ps.setString(11, addedBy == null ? null : (String) addedBy.get("reason"));
        ps.addBatch();
    }

    private static void claim(Path dir, String listId) throws IOException {
        Path marker = dir.resolve(MARKER);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            Files.writeString(marker, listId);
            return;
        }
        if (!Files.isRegularFile(marker) || !listId.equals(Files.readString(marker).trim()))
            throw new IllegalStateException("'" + dir.getFileName() + "' exists and is not this Entity List's sidecar");
    }
}
