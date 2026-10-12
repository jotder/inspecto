// package-demo-rebase.java - run by package-demo-bake.ps1 with the host JDK's source launcher (inspecto.jar on the classpath carries DuckDB JDBC):
//   java --enable-native-access=ALL-UNNAMED -cp inspecto.jar package-demo-rebase.java <duckdb-file> <old> <new> [<old> <new> ...]
// Replaces every <old> with <new> in every VARCHAR column of every base table, then CHECKPOINTs, so a baked Space's ledgers hold
// no build-time absolute path. Generic on purpose: the table layout belongs to the engine, not to this script.
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class PackageDemoRebase {
    public static void main(String[] a) throws Exception {
        if (a.length < 3 || a.length % 2 == 0) { System.err.println("usage: <duckdb-file> <old> <new> [<old> <new> ...]"); System.exit(2); }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + a[0]); Statement s = c.createStatement()) {
            List<String[]> cols = new ArrayList<>();
            try (ResultSet r = s.executeQuery("SELECT c.table_schema, c.table_name, c.column_name FROM information_schema.columns c "
                    + "JOIN information_schema.tables t USING (table_schema, table_name) WHERE t.table_type = 'BASE TABLE' AND c.data_type IN ('VARCHAR', 'JSON')")) {
                while (r.next()) cols.add(new String[]{r.getString(1), r.getString(2), r.getString(3)});
            }
            int rows = 0;
            for (String[] col : cols) {
                String q = "\"" + col[0] + "\".\"" + col[1] + "\"", n = "\"" + col[2] + "\"";
                for (int i = 1; i < a.length; i += 2) {
                    try (PreparedStatement u = c.prepareStatement("UPDATE " + q + " SET " + n + " = replace(" + n + ", ?, ?) WHERE contains(" + n + ", ?)")) {
                        u.setString(1, a[i]); u.setString(2, a[i + 1]); u.setString(3, a[i]);
                        rows += u.executeUpdate();
                    }
                }
            }
            s.execute("CHECKPOINT");
            // The UPDATEs leave the OLD strings in dead blocks of the file. Copy the live data into a fresh file and swap it in.
            String db; try (ResultSet r = s.executeQuery("SELECT current_database()")) { r.next(); db = r.getString(1); }
            java.nio.file.Path fresh = java.nio.file.Path.of(a[0] + ".rebased");
            java.nio.file.Files.deleteIfExists(fresh);
            s.execute("ATTACH '" + fresh.toString().replace("'", "''") + "' AS rebased");
            s.execute("COPY FROM DATABASE \"" + db + "\" TO rebased");
            // Proof, on the semantic values (a byte scan cannot see inside DuckDB's compressed strings): no <old> is left.
            for (String[] col : cols) {
                for (int i = 1; i < a.length; i += 2) {
                    try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM rebased.\"" + col[0] + "\".\"" + col[1] + "\" WHERE contains(\"" + col[2] + "\", ?)")) {
                        p.setString(1, a[i]);
                        try (ResultSet r = p.executeQuery()) { r.next(); if (r.getLong(1) > 0) { System.err.println("still holds '" + a[i] + "': " + col[1] + "." + col[2]); System.exit(1); } }
                    }
                }
            }
            s.execute("DETACH rebased");
            c.close();
            java.nio.file.Files.move(fresh, java.nio.file.Path.of(a[0]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("rebased " + a[0] + ": " + rows + " value(s) in " + cols.size() + " VARCHAR column(s)");
        }
    }
}
