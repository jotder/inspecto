package com.gamma.pipeline.exec;

/**
 * The one implementation of SQL quoting for this package (JAVA-5).
 *
 * <p>{@link RowShaper}, {@link ScratchTables} and {@link ComponentPreview} each carried a
 * byte-identical private copy of both methods. Quoting is exactly the kind of rule that must never
 * drift between copies: a divergence here is not a compile error but an injection or a mangled
 * identifier, and the failure surfaces in generated SQL, far from the copy that was edited.
 *
 * <p>The callers keep their own thin helpers so their call sites are unchanged; those helpers now
 * delegate here rather than re-implementing the escape.
 */
final class SqlIdent {

    private SqlIdent() {}

    /** A double-quoted SQL identifier, with embedded double quotes doubled. */
    static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** A single-quoted SQL string literal, with embedded single quotes doubled. */
    static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
