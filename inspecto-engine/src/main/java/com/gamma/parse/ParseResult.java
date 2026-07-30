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
@PublicApi(since = "5.3.0")
public sealed interface ParseResult permits ParseResult.Table, ParseResult.Tree {

    /** Flat parse output: the produced columns, a bounded row sample, and totals. */
    record Table(List<String> columns, List<Map<String, Object>> rows,
                 long rowCount, long rejectedRows) implements ParseResult {
        public Table {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
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
