package com.gamma.util;

/**
 * The one implementation of SQL quoting for the reactor (JAVA-5, widened by SQLIDENT-NINE-COPIES-1).
 *
 * <p>Quoting is exactly the kind of rule that must never drift between copies: a divergence here is
 * not a compile error but an injection or a mangled identifier, and the failure surfaces in
 * generated SQL, far from the copy that was edited. JAVA-5 made this the one implementation inside
 * {@code com.gamma.pipeline.exec}; it was still re-implemented byte-identically in a dozen further
 * classes across five modules, so the class moved here.
 *
 * <p><b>Why {@code inspecto-util} and not {@code inspecto-sql}:</b> two of the copies live in
 * {@code inspecto-util} itself ({@link SqlBuilder#quoteIdent} and {@code BrowsableStore}), and
 * {@code inspecto-sql} depends on {@code inspecto-util} — a home in {@code inspecto-sql} could not
 * be reached from here without a module cycle. {@code inspecto-util} is also the only module every
 * calling module already sees.
 *
 * <p>Callers keep their own thin private helpers so their call sites are unchanged; those helpers
 * delegate here rather than re-implementing the escape.
 *
 * <p>Both methods are total over non-null input and throw {@link NullPointerException} on null —
 * quoting a null identifier has no correct answer, and failing loudly beats emitting {@code ""}.
 */
public final class SqlIdent {

    private SqlIdent() {}

    /** A double-quoted SQL identifier, with embedded double quotes doubled. */
    public static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** A single-quoted SQL string literal, with embedded single quotes doubled. */
    public static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * A single-quoted SQL string literal naming a file: the absolute path with forward slashes (DuckDB reads
     * them on Windows too), embedded single quotes doubled. The one rendering of a path in generated SQL
     * (ANALYTICS-CORE-1 M9 retired five private copies of it).
     */
    public static String pathStr(java.nio.file.Path p) {
        return pathStr(p, "");
    }

    /**
     * {@link #pathStr(java.nio.file.Path)} of a directory with a glob appended inside the same literal —
     * {@code pathStr(dir, "/*}{@code /*.parquet")}. A glob cannot go through {@code Path.resolve}: {@code *} is
     * not a legal path character on Windows.
     */
    public static String pathStr(java.nio.file.Path dir, String globSuffix) {
        return sqlStr(dir.toAbsolutePath().toString().replace('\\', '/') + globSuffix);
    }
}
