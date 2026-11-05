package com.gamma.control;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the records of a JSON file are and which nested fields to spread into columns ({@code ONBOARDING-FIRST-RUN-1}
 * C1, use case UC-3). Paths use the engine's one dotted notation ({@code raw.fields[].selector} and
 * {@code json.records_path}): segments joined by {@code .}, a literal dot in a key escaped as {@code \.}.
 *
 * <p>The document is walked with Jackson's streaming parser, so a large file is read once and never held in memory.
 */
final class OnboardJson {

    private OnboardJson() {}

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code read_json_objects} reads one document of at most this size (DuckDB's default {@code maximum_object_size}). */
    static final long MAX_DOCUMENT = 16L * 1024 * 1024;

    /** A list of records found in a document: its path, how many records it holds. */
    record Candidate(String path, long records) {}

    /** Whether the file holds ONE top-level JSON value (a document), rather than one value per line (NDJSON). */
    static boolean singleDocument(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() == null) return false;
            p.skipChildren();
            return p.nextToken() == null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException notOneDocument) {
            return false;
        }
    }

    /**
     * Every list of objects in the document, in document order, outermost first; a list inside a record is a field of
     * that record, not a list of records. A document that IS a list of objects answers one candidate, {@code $}.
     */
    static List<Candidate> candidates(Path file) throws IOException {
        List<Candidate> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); JsonParser p = FACTORY.createParser(in)) {
            JsonToken t = p.nextToken();
            if (t == JsonToken.START_ARRAY) {
                long n = countObjects(p, p.nextToken());
                if (n > 0) out.add(new Candidate("$", n));
            } else if (t == JsonToken.START_OBJECT) {
                walk(p, new ArrayDeque<>(), out);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file is not valid JSON: "
                    + malformed.getOriginalMessage());
        }
        return out;
    }

    /** {@code p} is just inside an object; walk to its end. */
    private static void walk(JsonParser p, Deque<String> path, List<Candidate> out) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            path.addLast(p.currentName());
            JsonToken v = p.nextToken();
            if (v == JsonToken.START_OBJECT) {
                walk(p, path, out);
            } else if (v == JsonToken.START_ARRAY) {
                JsonToken first = p.nextToken();
                long n = countObjects(p, first);
                if (first == JsonToken.START_OBJECT) out.add(new Candidate(selector(new ArrayList<>(path)), n));
            }
            path.removeLast();
        }
    }

    /** From the first element token to the array's end: how many elements are objects. */
    private static long countObjects(JsonParser p, JsonToken first) throws IOException {
        long n = 0;
        for (JsonToken e = first; e != null && e != JsonToken.END_ARRAY; e = p.nextToken()) {
            if (e == JsonToken.START_OBJECT) n++;
            p.skipChildren();
        }
        return n;
    }

    /**
     * Segments as one dotted selector, a literal {@code .} in a key escaped as {@code \.} - exactly what the engine's
     * {@code DuckDbCsvIngester.selectorSegments} undoes (it unescapes nothing else, so a {@code \} is kept as it is).
     */
    static String selector(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (!sb.isEmpty()) sb.append('.');
            sb.append(s.replace(".", "\\."));
        }
        return sb.toString();
    }

    /** A dotted selector split on unescaped dots (the engine's {@code selectorSegments}). */
    static List<String> segments(String selector) {
        String p = selector.startsWith("$.") ? selector.substring(2) : selector;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length() && p.charAt(i + 1) == '.') cur.append(p.charAt(++i));
            else if (c == '.') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * The DuckDB JSON path of a segment list: {@code $."a"."b"}, built exactly as the engine builds it (no escaping), so a
     * key the engine cannot read fails the draft rather than the commit.
     */
    static String jsonPath(List<String> segments) {
        StringBuilder sb = new StringBuilder("$");
        for (String s : segments) sb.append(".\"").append(s).append('"');
        return sb.toString();
    }

    /**
     * The columns of the sampled records: each leaf path in first-seen order. A nested object is spread into its fields
     * (its path is listed in {@code flattened}); a list stays one column of JSON text ({@code lists}); a path that is an
     * object in some records and a value in others stays one column of JSON text ({@code mixed}), so no value is lost.
     */
    record Flat(List<List<String>> columns, List<String> flattened, List<String> lists, List<String> mixed) {}

    private static final int OBJECT = 1, VALUE = 2, LIST = 4;

    static Flat flatten(List<String> records) throws IOException {
        Map<String, List<String>> seg = new LinkedHashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String r : records) {
            JsonNode n = MAPPER.readTree(r);
            if (n != null && n.isObject()) visit(n, new ArrayList<>(), seg, kinds);
        }
        List<List<String>> columns = new ArrayList<>();
        List<String> flattened = new ArrayList<>(), lists = new ArrayList<>(), mixed = new ArrayList<>();
        List<String> leaves = new ArrayList<>();   // paths kept whole: nothing beneath them is a column
        for (var e : seg.entrySet()) {
            String path = e.getKey();
            if (leaves.stream().anyMatch(l -> path.startsWith(l + "."))) continue;
            int k = kinds.get(path);
            boolean spread = (k & OBJECT) != 0 && (k & (VALUE | LIST)) == 0
                    && seg.keySet().stream().anyMatch(o -> o.startsWith(path + "."));
            if (spread) {
                flattened.add(path);
                continue;
            }
            if ((k & OBJECT) != 0 && (k & (VALUE | LIST)) != 0) mixed.add(path);
            else if ((k & LIST) != 0) lists.add(path);
            leaves.add(path);
            columns.add(e.getValue());
        }
        return new Flat(columns, flattened, lists, mixed);
    }

    private static void visit(JsonNode obj, List<String> prefix, Map<String, List<String>> seg, Map<String, Integer> kinds) {
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            var f = it.next();
            List<String> path = new ArrayList<>(prefix);
            path.add(f.getKey());
            String key = selector(path);
            seg.putIfAbsent(key, path);
            JsonNode v = f.getValue();
            int kind = v.isObject() && !v.isEmpty() ? OBJECT : v.isArray() ? LIST : v.isNull() ? 0 : VALUE;
            kinds.merge(key, kind, (a, b) -> a | b);
            if (kind == OBJECT) visit(v, path, seg, kinds);
        }
    }
}
