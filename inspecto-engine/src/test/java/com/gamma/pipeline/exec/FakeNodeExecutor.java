package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A test-scope {@link PipelineNodeExecutor} provider, registered in
 * {@code src/test/resources/META-INF/services} — the same idiom {@code FakeRemoteConnectorFactory} uses
 * for the collector seam.
 *
 * <p>It contributes {@code transform.take}: keep the first {@code n} rows into {@code data} and divert
 * the rest to {@code dropped}, so the test proves a contributed type can emit MORE than one relation,
 * not merely run.
 *
 * <p>⚠ It contributes an ADDITIVE type only. A provider may also <b>override</b> a built-in (the registry
 * is consulted before the built-in chain), but that capability is deliberately NOT covered by a test
 * provider: {@code ServiceLoader} registration is global and resolved at class-load, so shadowing a core
 * verb here would change it for every other test in this module. The reason it is untested is the same
 * reason it is powerful.
 */
public final class FakeNodeExecutor implements PipelineNodeExecutor {

    /** The contributed type — deliberately NOT a {@code BuiltinNodeType}. */
    public static final String TYPE = "transform.take";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<RowShaper.Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix,
                                          RowShaper.ReferenceResolver references) throws SQLException {
        Object n = node.cfg("count");
        int keep = n == null ? 1 : Integer.parseInt(n.toString());
        String data = outPrefix + "__" + PipelineRel.DATA;
        String dropped = outPrefix + "__" + PipelineRel.DROPPED;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"" + data + "\" AS SELECT * FROM \"" + input + "\" LIMIT " + keep);
            st.execute("CREATE TABLE \"" + dropped + "\" AS SELECT * FROM \"" + input + "\" OFFSET " + keep);
        }
        return List.of(new RowShaper.Relation(PipelineRel.DATA, data),
                       new RowShaper.Relation(PipelineRel.DROPPED, dropped));
    }
}
