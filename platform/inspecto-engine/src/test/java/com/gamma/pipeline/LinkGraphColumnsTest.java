package com.gamma.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column links (`SPACE-LINK-CENSUS-1` LC-5): each COLUMN key and each SQL text resolved against the column set
 * its Schema / Dataset publishes - one resolved and one dangling holder per link kind, SQL that is not analysed,
 * the in-memory graph that judges none, and the consumers a column rename would break.
 */
class LinkGraphColumnsTest {

    @TempDir Path root;

    private void w(String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @BeforeEach
    void space() throws IOException {
        w("orders/orders_pipeline.toon", """
                name: orders
                dirs:
                  database: data/orders/database
                processing:
                  schema_file: orders_schema.toon
                output:
                  filename_column: SRC_FILE
                """);
        w("orders/orders_schema.toon", """
                mapping:
                  fields[4]{name,from,fn}:
                    ORDER_ID,ORDER_ID,keep
                    ORDER_DATE,ORDER_DATE,keep
                    REGION,REGION,keep
                    GROSS,GROSS,keep
                """);
        // a Pipeline whose mapping comes from a sibling CSV: its columns are not read, so links to it stay unchecked
        w("csvmap/csvmap_pipeline.toon", "name: csvmap\nprocessing:\n  schema_file: csvmap_schema.toon\n");
        w("csvmap/csvmap_schema.toon", "raw:\n  name: X\n");
        w("csvmap/csvmap_mapping.csv", "name,from,fn\nA,A,keep\n");

        w("registry/datasets/ds.toon", """
                physicalRef: orders
                dateField: ORDER_DATE
                calculated[1]{name,expr}:
                  taxed,"round(gross * 1.18, 2)"
                """);
        w("registry/datasets/ds_bad.toon", """
                physicalRef: orders/database
                dateField: SHIP_DATE
                calculated[1]{name,expr}:
                  margin,"round(unit_price * 0.35, 2)"
                """);
        w("registry/datasets/ds_job.toon", "physicalRef: job_made\ndateField: anything\n");
        w("registry/datasets/ds_declared.toon", """
                physicalRef: job_made
                columns[2]{name,type}:
                  REGION,string
                  total,number
                dateField: total
                """);
        w("registry/datasets/ds_virtual.toon", """
                kind: virtual
                sourceName: orders
                sql: "SELECT REGION, SHIP_MODE FROM orders WHERE GROSS > 0"
                """);
        w("registry/widgets/w_ok.toon", """
                datasetId: ds
                controls:
                  x[1]{field}:
                    region
                  y[1]{field,agg}:
                    taxed,sum
                """);
        w("registry/widgets/w_bad.toon", """
                datasetId: ds
                controls:
                  value:
                    field: NET
                """);
        w("registry/expectations/e_ok.toon", "target: orders\ncolumn: \" GROSS \"\nkind: non_null\n");
        w("registry/expectations/e_bad.toon", "targetType: pipeline\ntarget: orders\ncolumn: NETT\nkind: non_null\n");
        w("registry/expectations/e_cond.toon", """
                target: orders
                kind: condition
                when:
                  kind: group
                  op: AND
                  items[2]{kind,field,operator,value}:
                    condition,REGION,"=",N
                    condition,COUNTRY,"=",X
                """);
        w("registry/decision-rules/r_ok.toon", """
                targetType: pipeline
                target: orders
                when:
                  kind: group
                  op: AND
                  items[1]{kind,field,operator,value}:
                    condition,gross,">","100"
                """);
        w("registry/decision-rules/r_bad.toon", """
                target: orders
                when:
                  kind: group
                  op: AND
                  items[1]{kind,field,operator,value}:
                    condition,DISCOUNT,">","1"
                """);
        w("registry/decision-rules/r_job.toon", """
                targetType: job
                target: nightly
                when:
                  kind: group
                  op: AND
                  items[1]{kind,field,operator,value}:
                    condition,WHATEVER,">","1"
                """);
        w("registry/reconciliations/rc.toon", """
                leftDataset: ds
                rightDataset: ds_declared
                keyColumns[1]: REGION
                compareColumns[2]{column,agg}:
                  total,sum
                  REGION,count
                """);
        w("registry/queries/q_ok.toon", "type: sql\ndatasetId: ds\ntext: \"SELECT region, SUM(taxed) AS t FROM ds GROUP BY region ORDER BY t\"\n");
        w("registry/queries/q_bad.toon", "type: sql\ndatasetId: ds\ntext: \"SELECT CHANNEL FROM ds\"\n");
        w("registry/queries/q_sub.toon", "type: sql\ndatasetId: ds\ntext: \"SELECT * FROM (SELECT region FROM ds) s\"\n");
        w("registry/queries/q_broken.toon", "type: sql\ndatasetId: ds\ntext: \"SELEC nonsense FROM\"\n");
        w("orders/daily_enrich.toon", """
                input:
                  database: data/orders/database
                references:
                  dim:
                    path: data/ref/dim.csv
                transform: "SELECT i.year, i.REGION, d.zone, i.SHIPPER, SUM(i.GROSS) AS rev FROM input i LEFT JOIN dim d ON i.REGION = d.region GROUP BY ALL"
                """);
    }

    private LinkGraph graph() {
        return LinkGraph.of(root, rel -> false);
    }

    private static List<String> view(List<LinkGraph.Edge> edges) {
        return edges.stream().map(e -> e.fromName() + " " + e.key() + "=" + e.value() + " " + e.status()).toList();
    }

    @Test
    void datasetDateFieldAndCalculatedExpressions() {
        LinkGraph g = graph();
        assertEquals(List.of("ds dateField=ORDER_DATE RESOLVED", "ds_bad dateField=SHIP_DATE DANGLING",
                        "ds_declared dateField=total RESOLVED", "ds_job dateField=anything UNCHECKED"),
                view(g.edges("dataset", "dateField")).stream().sorted().toList(),
                "a store no Pipeline writes, with no declared columns, is not judged");
        assertEquals(List.of("ds calculated[0].expr=gross RESOLVED", "ds_bad calculated[0].expr=unit_price DANGLING"),
                view(g.edges("dataset", "calculated[].expr")).stream().sorted().toList());
    }

    @Test
    void widgetControlsMatchCaseInsensitivelyIncludingCalculatedColumns() {
        assertEquals(List.of("w_bad controls.value.field=NET DANGLING", "w_ok controls.x[0].field=region RESOLVED",
                        "w_ok controls.y[0].field=taxed RESOLVED"),
                view(graph().columnEdges().stream().filter(e -> e.fromKind().equals("widget")).toList())
                        .stream().sorted().toList());
    }

    @Test
    void expectationColumnAndConditionFields() {
        LinkGraph g = graph();
        assertEquals(List.of("e_bad column=NETT DANGLING", "e_ok column= GROSS  RESOLVED"),
                view(g.edges("expectation", "column")).stream().sorted().toList(), "the loader trims column");
        assertEquals(List.of("e_cond when.items[0].field=REGION RESOLVED", "e_cond when.items[1].field=COUNTRY DANGLING"),
                view(g.edges("expectation", "when.**.field")));
    }

    @Test
    void decisionRuleConditionFieldsOnlyForAPipelineTarget() {
        assertEquals(List.of("r_bad when.items[0].field=DISCOUNT DANGLING", "r_job when.items[0].field=WHATEVER UNCHECKED",
                        "r_ok when.items[0].field=gross RESOLVED"),
                view(graph().edges("decision-rule", "when.**.field")).stream().sorted().toList());
    }

    @Test
    void reconciliationColumnsMustExistOnEveryKnownSide() {
        LinkGraph g = graph();
        assertEquals(List.of("rc keyColumns[0]=REGION RESOLVED"), view(g.edges("reconciliation", "keyColumns[]")));
        assertEquals(List.of("rc compareColumns[0].column=total DANGLING", "rc compareColumns[1].column=REGION RESOLVED"),
                view(g.edges("reconciliation", "compareColumns[].column")), "total is on one side only");
    }

    @Test
    void querySqlResolvedDanglingAndNotAnalysed() {
        List<String> q = view(graph().edges("query", "text")).stream().sorted().toList();
        assertEquals(List.of("q_bad text=CHANNEL DANGLING",
                "q_broken text=the DuckDB parser does not read it as one SELECT NOT_ANALYSED",
                "q_ok text=region RESOLVED", "q_ok text=taxed RESOLVED",
                "q_sub text=a subquery is not analysed NOT_ANALYSED"), q,
                "an output alias (t) is not a column; unreadable SQL is not analysed, never dangling");
    }

    @Test
    void enrichmentTransformAttributesByAliasAndLeavesUnknownRelationsNotAnalysed() {
        assertEquals(List.of("daily_enrich transform=year RESOLVED", "daily_enrich transform=REGION RESOLVED",
                        "daily_enrich transform=zone NOT_ANALYSED", "daily_enrich transform=SHIPPER DANGLING",
                        "daily_enrich transform=GROSS RESOLVED", "daily_enrich transform=region NOT_ANALYSED"),
                view(graph().edges("enrichment", "transform")),
                "input = the store under input.database; a path reference's columns are unknown");
    }

    @Test
    void virtualDatasetSqlReadsItsSourceStore() {
        LinkGraph g = graph();
        assertEquals(List.of("ds_virtual sql=REGION RESOLVED", "ds_virtual sql=SHIP_MODE DANGLING", "ds_virtual sql=GROSS RESOLVED"),
                view(g.edges(LinkCensus.of("dataset", "sql", "column"))));
        assertEquals(List.of("ds_virtual sql=SELECT REGION, SHIP_MODE FROM orders WHERE GROSS > 0 UNCHECKED"),
                view(g.edges("dataset", "sql")), "the store link on the same key is unchanged");
    }

    @Test
    void verifierFindingsSchemaPartitionsAliasListsAndRegistrySchemaRefs() throws IOException {
        // a Schema's partitions[] column is a store column
        w("part/part_pipeline.toon", "name: part\nprocessing:\n  schema_file: part_schema.toon\n");
        w("part/part_schema.toon", """
                partitions[1]{column,source,type}:
                  item_category,CATEGORY,VARCHAR
                mapping:
                  fields[1]{name,from,fn}:
                    ITEM,ITEM,keep
                """);
        w("registry/expectations/e_part.toon", "target: part\ncolumn: item_category\nkind: non_null\n");
        // schema/<id> names registry/schemas/<id>.toon (PipelineConfigParser.SCHEMA_REF_PREFIX)
        w("reg/reg_pipeline.toon", "name: reg\nprocessing:\n  schema_file: schema/regs\n");
        w("reg/registry/schemas/regs.toon", "mapping:\n  fields[1]{name,from,fn}:\n    A,A,keep\n");
        w("registry/expectations/e_reg.toon", "target: reg\ncolumn: NOPE\nkind: non_null\n");
        // a table column-alias list renames the columns: not analysed, never dangling
        w("registry/datasets/ds_alias.toon", "kind: virtual\nsourceName: orders\nsql: \"SELECT a FROM orders t(a, b)\"\n");
        LinkGraph g = graph();
        assertEquals(List.of("e_part column=item_category RESOLVED", "e_reg column=NOPE DANGLING"),
                view(g.edges("expectation", "column").stream()
                        .filter(e -> e.fromName().equals("e_part") || e.fromName().equals("e_reg")).toList())
                        .stream().sorted().toList());
        assertEquals(List.of(LinkGraph.Status.NOT_ANALYSED), g.edges(LinkCensus.of("dataset", "sql", "column")).stream()
                .filter(e -> e.fromName().equals("ds_alias")).map(LinkGraph.Edge::status).toList());
    }

    @Test
    void anUnreadMappingKeepsItsLinksUnchecked() throws IOException {
        w("registry/expectations/e_csv.toon", "target: csvmap\ncolumn: ZZZ\nkind: non_null\n");
        assertEquals(List.of("e_csv column=ZZZ UNCHECKED"), view(graph().edges("expectation", "column").stream()
                .filter(e -> e.fromName().equals("e_csv")).toList()));
    }

    @Test
    void danglingColumnsAreTheIntegrityFindingsAndTheInMemoryGraphJudgesNone() {
        assertEquals(List.of(
                "broken column reference: enrichment 'daily_enrich' transform → missing column 'SHIPPER'",
                "broken column reference: expectation 'e_bad' column → missing column 'NETT'",
                "broken column reference: expectation 'e_cond' when.items[1].field → missing column 'COUNTRY'",
                "broken column reference: decision-rule 'r_bad' when.items[0].field → missing column 'DISCOUNT'",
                "broken column reference: dataset 'ds_bad' calculated[0].expr → missing column 'unit_price'",
                "broken column reference: dataset 'ds_bad' dateField → missing column 'SHIP_DATE'",
                "broken column reference: dataset 'ds_virtual' sql → missing column 'SHIP_MODE'",
                "broken column reference: widget 'w_bad' controls.value.field → missing column 'NET'",
                "broken column reference: reconciliation 'rc' compareColumns[0].column → missing column 'total'",
                "broken column reference: query 'q_bad' text → missing column 'CHANNEL'"),
                ComponentIntegrity.brokenColumns(graph()).stream().sorted(this::byKindOrder).toList());
        Map<String, List<ComponentRegistry.Component>> byType = Map.of("widget", List.of(new ComponentRegistry.Component(
                "widget", "w", null, Map.of("datasetId", "nope", "controls", Map.of("value", Map.of("field", "NET"))))));
        LinkGraph mem = LinkGraph.ofComponents(byType, null);
        assertTrue(mem.columnEdges().stream().allMatch(e -> e.status() == LinkGraph.Status.UNCHECKED));
        assertTrue(ComponentIntegrity.brokenColumns(mem).isEmpty(), "the import pre-check universe judges no column");
    }

    private int byKindOrder(String a, String b) {
        List<String> order = List.of("enrichment", "expectation", "decision-rule", "dataset", "widget", "reconciliation", "query");
        int ka = order.indexOf(a.split(" ")[3]), kb = order.indexOf(b.split(" ")[3]);
        return ka != kb ? Integer.compare(ka, kb) : a.compareTo(b);
    }

    @Test
    void columnConsumersListWhatARenameOfOneMappedColumnBreaks() {
        Path schema = root.resolve("orders/orders_schema.toon");
        // sorted: the registry lists components in directory order, which is not stable across platforms
        assertEquals(List.of("daily_enrich transform=GROSS", "ds calculated[0].expr=gross", "ds_virtual sql=GROSS",
                        "e_ok column= GROSS ", "r_ok when.items[0].field=gross"),
                graph().columnConsumers(schema, "gross").stream()
                        .map(e -> e.fromName() + " " + e.key() + "=" + e.value()).sorted().toList());
        assertTrue(graph().columnConsumers(schema, "year").isEmpty(), "a partition column is the store's, not the Schema's");
        assertTrue(graph().columnConsumers(root.resolve("csvmap/csvmap_schema.toon"), "A").isEmpty());
    }
}
