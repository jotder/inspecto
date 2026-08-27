package com.gamma.parse;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;

/**
 * What a {@link ParserPlugin} preview produced from a sample: a flat {@link Table} for tabular
 * formats (delimited, fixed-width, …) or a record {@link Tree} for hierarchical ones (XML,
 * ASN.1, …). Tree-shaped data cannot honestly load to Tables until a flatten configuration maps
 * it onto segment schemas — until then a Tree result is a preview/authoring aid, which is exactly
 * how the UI presents it.
 *
 * <p>The shapes mirror the UI's {@code ParserPreview} union verbatim ({@code kind: 'table' |
 * 'tree'}), so the control plane serializes a result without translation.
 */
@PublicApi(since = "4.0.0")
public sealed interface ParseResult permits ParseResult.Table, ParseResult.Tree {

    /**
     * Flat parse output: the produced columns, a bounded row sample, and totals.
     * {@code columnTypes} (B2, 5.4.0, additive) is a per-column {@code {name, type}} list from an
     * {@code auto_detect} sniff of the sample — advisory (ingest stays all-VARCHAR), empty when the
     * format has no sniff.
     */
    record Table(List<String> columns, List<Map<String, Object>> rows,
                 long rowCount, long rejectedRows,
                 List<Map<String, String>> columnTypes) implements ParseResult {
        public Table {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
            columnTypes = columnTypes == null ? List.of() : List.copyOf(columnTypes);
        }

        /** The pre-B2 shape — no inferred types (plugins and non-sniffing formats). */
        public Table(List<String> columns, List<Map<String, Object>> rows, long rowCount, long rejectedRows) {
            this(columns, rows, rowCount, rejectedRows, List.of());
        }
    }

    /** Hierarchical parse output: a bounded forest of decoded records. */
    record Tree(long recordCount, List<Node> nodes) implements ParseResult {
        public Tree {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /**
     * One node of a decoded record tree.
     *
     * @param label    the element/field name (never {@code null})
     * @param type     an optional short type tag shown as a chip (e.g. {@code element},
     *                 {@code string}, {@code SEQUENCE}); may be {@code null}
     * @param value    the decoded scalar at a leaf; {@code null} for container nodes
     * @param children child nodes (empty at a leaf)
     */
    record Node(String label, String type, String value, List<Node> children) {
        public Node {
            label = label == null ? "" : label;
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** A leaf node carrying a scalar value. */
        public static Node leaf(String label, String type, String value) {
            return new Node(label, type, value, List.of());
        }

        /** A container node with children. */
        public static Node container(String label, String type, List<Node> children) {
            return new Node(label, type, null, children);
        }
    }
}
