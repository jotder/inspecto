package com.gamma.control;

import com.gamma.config.safety.PathJail;
import com.gamma.parse.ParseResult;
import com.gamma.parse.ParserPlugin;
import com.gamma.parse.Parsers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.gamma.util.Values.mapAt;

/**
 * Parser-catalog routes: the self-describing {@link ParserPlugin} registry served to
 * authoring UIs. {@code GET /parsers} lists every registered parser with its grammar schema (the
 * same {@code FieldSpec} vocabulary {@code GET /config/spec/&#123;type&#125;} serves), and
 * {@code POST /parsers/&#123;id&#125;/preview} parses a sample with an in-progress grammar —
 * stateless and scratch-only, the grammar-shaped sibling of
 * {@code POST /config/preview/parsing} (which stays the draft-true path for pipeline drafts).
 * Both are read/compute-only: no write gate, and no capability — except that previewing a parser a Job
 * Pack contributed needs {@code canAuthorWorkbench} (operator D4 2026-09-25): that is third-party code
 * over caller-chosen bytes, so it sits behind the same bar as authoring the Pipeline that ingests with it.
 */
final class ParserRoutes implements RouteModule {

    /** Character cap on {@code sample_text} — a preview sample, not a data upload. */
    private static final int MAX_SAMPLE_CHARS = 1_000_000;
    /** Byte cap on the decoded {@code sample_b64} — binary formats need bytes, still not an upload. */
    private static final int MAX_SAMPLE_BYTES = 4 * 1024 * 1024;

    @Override
    public void register(ApiContext api) {
        api.get("/parsers", (e, m) -> catalog());
        api.post("/parsers/([^/]+)/preview", (e, m) -> preview(api, e, ApiContext.name(m), api.body(e)));
    }

    private static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParserPlugin p : Parsers.catalog()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("label", p.label());
            row.put("hierarchical", p.hierarchical());
            // Preview and ingest are separate capabilities: a hierarchical parser without an
            // ingester previews today but cannot load to Tables until the flatten configuration.
            row.put("ingestable", Parsers.ingestable(p));
            // The FQCN a guided Save writes to `parsing.plugin.ingester` — absent for the built-ins
            // (they ingest through the engine's own DuckDB path, not a named class) and for
            // preview-only plugins. Serving it is what lets the segments editor author the block
            // without the UI hardcoding any parser's implementation class.
            p.ingesterClass().ifPresent(fqcn -> row.put("ingesterClass", fqcn));
            // Provenance (slice P2): builtin | classpath | pack:<jar filename> — the Job Type vocabulary.
            row.put("source", Parsers.sourceOf(p.id()));
            row.put("grammarSchema", p.grammarSchema());
            out.add(row);
        }
        return out;
    }

    private static Object preview(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String id,
                                  Map<String, Object> body) {
        ParserPlugin parser = Parsers.get(id)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "unknown parser: " + id));
        // Operator D4 (parser-plugins-trust-design.md slice P4, 2026-09-25): a Job Pack's parser is
        // third-party code over caller-chosen bytes, so previewing one takes the capability that authors
        // the Pipeline which would ingest with it. Built-in and classpath parsers stay open (read-shaped).
        // Gated IN the handler because the gate depends on the parser — recorded as an exemption with
        // this reason in CapabilityManifest. Checked before any pack code runs.
        if (Parsers.ownerOf(id).isPresent()) ApiContext.requireCapability(ex, "canAuthorWorkbench");
        byte[] sample = sampleOf(body);
        Map<String, Object> grammar = grammarOf(body);
        Path configDir = configDirOf(api, body);
        try {
            ParseResult r = parser.preview(sample, grammar, configDir);
            return toJson(r);
        } catch (PathJail.Escape escape) {
            // A grammar FILE reference (asn1.grammar_file) outside the allowed roots — the jail's verdict.
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, escape.getMessage());
        } catch (IllegalArgumentException callerError) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, callerError.getMessage());
        } catch (Exception parseFail) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "sample does not parse with this grammar: " + parseFail.getMessage());
        }
    }

    /** The sample bytes: {@code sample_text} (text formats) or {@code sample_b64} (binary), capped. */
    private static byte[] sampleOf(Map<String, Object> body) {
        String text = ApiContext.str(body, "sample_text");
        String b64 = ApiContext.str(body, "sample_b64");
        if ((text == null || text.isBlank()) && (b64 == null || b64.isBlank()))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'sample_text' or 'sample_b64'");
        if (text != null && !text.isBlank()) {
            if (text.length() > MAX_SAMPLE_CHARS)
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "sample_text too large (max " + MAX_SAMPLE_CHARS + " chars)");
            return text.getBytes(StandardCharsets.UTF_8);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException notB64) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "sample_b64 is not valid base64");
        }
        if (bytes.length > MAX_SAMPLE_BYTES)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "sample_b64 too large (max " + MAX_SAMPLE_BYTES + " bytes)");
        return bytes;
    }

    /**
     * The Pipeline context: {@code subdir} is the directory the Pipeline's config file lives in, relative
     * to the write root — the same meaning as every satellite route's {@code subdir} ({@code ''} = a
     * Pipeline at the root). A relative grammar-file ref then resolves beside the Pipeline, the base the
     * Pipeline itself resolves it from, so one spelling previews and ingests ({@code BUNDLE-ASN1-GRAMMAR-FILE-1}).
     * Absent = no Pipeline context → {@code null}, the plugin's Space-root fallback. Not jailed here: the
     * resolved FILE is jailed once, by {@code Asn1GrammarSource}.
     */
    private static Path configDirOf(ApiContext api, Map<String, Object> body) {
        if (!body.containsKey("subdir")) return null;
        String subdir = ApiContext.str(body, "subdir");
        Path sub = Path.of(subdir == null ? "" : subdir.trim());
        if (sub.isAbsolute()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "subdir must be relative");
        Path root = api.writeRoot();
        return root == null ? null : root.resolve(sub).normalize();
    }

    private static Map<String, Object> grammarOf(Map<String, Object> body) {
        Object g = body.get("grammar");
        if (g == null) return Map.of();
        if (!(g instanceof Map<?, ?>)) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'grammar' must be a map of options");
        return mapAt(body, "grammar");
    }

    /** Serialize a {@link ParseResult} to the UI's {@code ParserPreview} union — nulls omitted. */
    private static Map<String, Object> toJson(ParseResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (r instanceof ParseResult.Table t) {
            out.put("kind", "table");
            out.put("columns", t.columns());
            out.put("rows", t.rows());
            out.put("rowCount", t.rowCount());
            out.put("rejectedRows", t.rejectedRows());
            // B2, additive: per-column inferred types from the auto_detect sniff — old clients
            // ignore the key; formats without a sniff simply omit it.
            if (!t.columnTypes().isEmpty()) out.put("columnTypes", t.columnTypes());
            // Additive (AUTHORING-REDESIGN-1 (i)): what the dialect sniff resolves the sample to, per option.
            if (!t.resolved().isEmpty()) out.put("resolved", t.resolved());
        } else if (r instanceof ParseResult.Tree t) {
            out.put("kind", "tree");
            out.put("recordCount", t.recordCount());
            out.put("nodes", nodesJson(t.nodes()));
        }
        return out;
    }

    private static List<Map<String, Object>> nodesJson(List<ParseResult.Node> nodes) {
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (ParseResult.Node n : nodes) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("label", n.label());
            if (n.type() != null) node.put("type", n.type());
            if (n.value() != null) node.put("value", n.value());
            if (!n.children().isEmpty()) node.put("children", nodesJson(n.children()));
            out.add(node);
        }
        return out;
    }
}
