package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.PipelineNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * <b>The execution half of the node-type plugin seam.</b> A provider shapes rows for one node
 * {@code type()}, so a contributed node type can actually run instead of only rendering.
 *
 * <p><b>The gap this closes.</b> {@link com.gamma.pipeline.PipelineNodeType} is ServiceLoader'd and
 * gives a contributed type a palette entry, a category, and {@code accepts}/{@code emits} the validator
 * enforces — but it is <em>descriptor-level only</em>, and execution dispatches through
 * {@link RowShaper#shape} , which used to be a closed {@code if}-chain over the built-ins. A contributed
 * type therefore rendered, validated and lifted, and then threw <i>"RowShaper cannot shape node type"</i>
 * at run time. Implementing this interface is what makes it executable.
 *
 * <p><b>Registration</b> mirrors every other seam here ({@code CollectorConnectorFactory},
 * {@code ParserPlugin}, {@code PipelineNodeType}): list the provider in
 * {@code META-INF/services/com.gamma.pipeline.exec.PipelineNodeExecutor}. It needs a public no-arg
 * constructor.
 *
 * <p>⚠ <b>A provider may override a built-in</b> by declaring the same {@link #type()} — the executor
 * registry is consulted <em>before</em> the built-in chain, deliberately mirroring
 * {@link com.gamma.pipeline.PipelineNodeTypes}, where providers are layered last so "an edition can
 * specialise a node type without forking the core". The trade is the same one that seam already
 * accepts: a provider can silently change what a core verb does.
 *
 * <p>⚠ <b>Single-input only.</b> This seam covers the one-input shaping {@link RowShaper#shape} does.
 * Multi-input fan-in ({@code transform.merge}) goes through {@link RowShaper#merge}, which is a
 * different signature and is deliberately not part of this contract yet — a provider that needs fan-in
 * should say so rather than have it half-work.
 *
 * <h2>The contract a provider must honour</h2>
 * <ul>
 *   <li><b>Create its own output tables</b> on {@code conn}, named by
 *       {@code outPrefix} + the relationship, and return one {@link RowShaper.Relation} per table. The
 *       caller reads those tables by the names returned; nothing renames them afterwards.</li>
 *   <li><b>Emit only relationships its descriptor declares.</b> {@code PipelineValidator} rejects an
 *       outbound edge whose relationship the type does not {@code emits()}, so a relation returned here
 *       that the descriptor never declared can be wired nowhere.</li>
 *   <li><b>Read nothing outside {@code input}</b> except through the supplied
 *       {@link RowShaper.ReferenceResolver} — the preview runs this on a <em>sealed</em> connection
 *       ({@code enable_external_access=false}), so file access fails there and would be a surprise only
 *       in production.</li>
 * </ul>
 */
@PublicApi(since = "4.0.0")
public interface PipelineNodeExecutor {

    /** The node {@code type} discriminator this executor shapes, e.g. {@code "transform.myverb"}. */
    String type();

    /**
     * Shape {@code node}'s single input into its output relations.
     *
     * @param conn       the batch/scratch connection; the executor creates its tables here
     * @param node       the node being executed — its config is the operator's authored block
     * @param input      the table name holding the input rows
     * @param outPrefix  the prefix output tables must be named under (one table per relationship)
     * @param references resolves a Reference Dataset to a readable relation; refuses when the caller
     *                   carries no reference context ({@link RowShaper.ReferenceResolver#NONE})
     * @return one entry per produced relation, in the order the node declares them
     * @throws SQLException if the shaping SQL fails — the batch fails with it, as for a built-in
     */
    List<RowShaper.Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix,
                                   RowShaper.ReferenceResolver references) throws SQLException;
}
