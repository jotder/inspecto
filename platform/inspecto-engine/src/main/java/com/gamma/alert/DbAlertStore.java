package com.gamma.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The durable {@link AlertStore} — the operational-store family {@code ALERTS} (one DuckDB file per Space by
 * default, PostgreSQL through {@code -Dinspecto.db=postgres}). One table, portable SQL (VARCHAR + BIGINT only),
 * attributes and the fired Alert kept as JSON text so a new attribute never needs a migration.
 *
 * <p>Active-Alert reads pull the Space's non-terminal rows of one scope and filter on the JSON in Java: that set
 * is the open Alerts (small by construction — a resolved Alert leaves it), never the history.
 */
public final class DbAlertStore implements AlertStore {

    private static final Logger log = LoggerFactory.getLogger(DbAlertStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS inspecto_alerts (
                id           VARCHAR NOT NULL PRIMARY KEY,
                scope        VARCHAR NOT NULL,
                severity     VARCHAR,
                title        VARCHAR,
                message      VARCHAR,
                attrs_json   VARCHAR NOT NULL,
                fired_json   VARCHAR,
                state        VARCHAR NOT NULL,
                incident_id  VARCHAR,
                opened_at    BIGINT  NOT NULL,
                closed_at    BIGINT  NOT NULL,
                closed_by    VARCHAR
            )""";

    private static final String COLS =
            "id, scope, severity, title, message, attrs_json, fired_json, state, incident_id, opened_at, closed_at, closed_by";

    private final ConnectionSource src;

    public DbAlertStore(String jdbcUrl, String user, String password) throws SQLException {
        this(JdbcDrivers.source(jdbcUrl, user, password, "alerts"));
    }

    /** Test/embedder seam: bring your own connection. */
    public DbAlertStore(Connection conn) throws SQLException {
        this(JdbcDrivers.source(conn));
    }

    public DbAlertStore(ConnectionSource src) throws SQLException {
        this.src = src;
        src.run(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(DDL);
            }
        });
        log.info("[ALERTS] store open");
    }

    @Override public void insert(Row r) {
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO inspecto_alerts (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, r.id());
                    ps.setString(2, r.scope());
                    ps.setString(3, r.severity());
                    ps.setString(4, r.title());
                    ps.setString(5, r.message());
                    ps.setString(6, json(r.attributes()));
                    ps.setString(7, r.fired() == null ? null : json(r.fired().toMap()));
                    ps.setString(8, r.state());
                    ps.setString(9, r.incidentId());
                    ps.setLong(10, r.openedAt());
                    ps.setLong(11, r.closedAt());
                    ps.setString(12, r.closedBy());
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not store Alert " + r.id() + ": " + e.getMessage(), e);
        }
    }

    @Override public boolean transition(String alertId, String action, String actor) {
        try {
            return src.with(conn -> {
                String state = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT state FROM inspecto_alerts WHERE id = ?")) {
                    ps.setString(1, alertId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) state = rs.getString(1);
                    }
                }
                if (state == null) return false;
                Optional<String> next = WORKFLOW.apply(state, action);
                if (next.isEmpty()) return false;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE inspecto_alerts SET state = ?, closed_at = ?, closed_by = ? WHERE id = ?")) {
                    boolean terminal = WORKFLOW.isTerminal(next.get());
                    ps.setString(1, next.get());
                    ps.setLong(2, terminal ? System.currentTimeMillis() : 0L);
                    ps.setString(3, terminal ? actor : null);
                    ps.setString(4, alertId);
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not move Alert " + alertId + ": " + e.getMessage(), e);
        }
    }

    @Override public List<Row> active(String scope) {
        return query("SELECT " + COLS + " FROM inspecto_alerts WHERE scope = ? AND state <> 'RESOLVED' ORDER BY opened_at DESC, id DESC",
                scope == null ? "" : scope);
    }

    @Override public List<Row> allActive() {
        return query("SELECT " + COLS + " FROM inspecto_alerts WHERE state <> 'RESOLVED' ORDER BY opened_at DESC, id DESC");
    }

    @Override public void linkIncident(String alertId, String incidentId) {
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE inspecto_alerts SET incident_id = ? WHERE id = ?")) {
                    ps.setString(1, incidentId);
                    ps.setString(2, alertId);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not link Alert " + alertId + ": " + e.getMessage(), e);
        }
    }

    @Override public Optional<Row> get(String alertId) {
        return query("SELECT " + COLS + " FROM inspecto_alerts WHERE id = ?", alertId).stream().findFirst();
    }

    @Override public List<Row> recentRows(int limit) {
        List<Row> out = new ArrayList<>();
        for (Row r : query("SELECT " + COLS + " FROM inspecto_alerts WHERE fired_json IS NOT NULL"
                + " ORDER BY opened_at DESC, id DESC LIMIT " + Math.max(0, limit)))
            if (r.fired() != null) out.add(r);
        return out;
    }

    @Override public long size() {
        try {
            return src.with(conn -> {
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM inspecto_alerts")) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not count Alerts: " + e.getMessage(), e);
        }
    }

    @Override public void close() {
        try {
            src.close();
        } catch (Exception e) {
            log.debug("alerts store close: {}", e.getMessage());
        }
    }

    private List<Row> query(String sql, String... params) {
        try {
            return src.with(conn -> {
                List<Row> out = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(row(rs));
                    }
                }
                return out;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not read Alerts: " + e.getMessage(), e);
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        Alert fired = null;
        String fj = rs.getString(7);
        if (fj != null) {
            try {
                fired = Alert.fromMap(JSON.readValue(fj, new TypeReference<LinkedHashMap<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("unreadable fired Alert for {}: {}", rs.getString(1), e.getMessage());
            }
        }
        Map<String, String> attrs;
        try {
            attrs = JSON.readValue(rs.getString(6), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            attrs = Map.of();
        }
        return new Row(rs.getString(1), rs.getString(4), rs.getString(5), rs.getString(3), rs.getString(2), attrs,
                rs.getString(8), rs.getLong(10), rs.getLong(11), rs.getString(12), rs.getString(9), fired);
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
