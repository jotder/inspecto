package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * <b>Push ingest</b> (ASSURE-PUSH-INGEST-1, wave 4.1): {@code POST /streams/{id}/records} lands a batch of NDJSON
 * or CSV records as ONE file in the Stream's inbox ({@code dirs.poll} of the Pipeline its Collector feeds), so
 * the ordinary Collector path ingests it — no second ingest path, no new commit semantics.
 *
 * <p>{@code {id}} is the Collector id, which is what {@code GET /catalog/streams} lists as a Stream's id.
 *
 * <h3>Gates, in order</h3>
 * {@code canOperateRuns} (the {@code /collectors/{id}/notify} precedent — a push, like a notify, makes a run
 * happen; a narrower capability would need a new seeded role literal and buys no separation today) · 429 the
 * per-caller push bucket ({@code ControlApi.rateLimit}) · 404 unknown Stream · 501 a Dataset-fed Pipeline (no
 * inbox) · 409 an authoring template, or the same {@code Idempotency-Key} already IN FLIGHT · 415 a content
 * type that is neither NDJSON nor CSV · 413 over the byte cap (declared length first) or the record cap ·
 * 422 not UTF-8, a malformed line (named by line number), no records, or a file name the Pipeline's
 * {@code file_pattern} would never pick up · then ONE atomic temp+move into the inbox. Every refusal lands
 * nothing.
 *
 * <p><b>No write-root gate and no approval hold, deliberately.</b> Both belong to CONFIG writes: the write root
 * jails what an author may change, and the maker-checker hold governs changes to how the Space behaves. A push
 * is DATA arriving at a Stream — exactly what a Collector landing a fetched file does with neither — so it is
 * gated as an operation, bounded, rate-limited and audited instead. The inbox path is server-derived from
 * the Pipeline config and the file name is server-minted, so there is no caller-supplied path to jail.
 *
 * <h3>Idempotency</h3>
 * The W5 stage (after auth, SEC-IDEMPOTENCY-REPLAY-1) replays a finished keyed response to the SAME caller
 * ({@code Idempotency-Replayed: true}, the first {@code file} name); its key is Space + principal + path, with a
 * body hash. It does not reserve a key while the first request runs, and here a concurrent duplicate would land
 * twice — so this route reserves that caller-scoped key for the life of the request (409 to a duplicate; retry →
 * replay). A keyed body over the stage's hash window runs un-keyed there, so this route refuses it (413). ⚠ The
 * store is per process and in memory: a replay after a restart re-lands the batch. Row-level dedup, when a Dataset needs it, keys on
 * the payload downstream — the same contract the Kafka lane states.
 *
 * <p><b>Audit:</b> one AUDIT row, {@code stream.records_pushed}, carrying {@code records}, {@code bytes} and the
 * landed {@code file} — never the payload. Nothing of the body is logged.
 */
final class StreamPushRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(StreamPushRoutes.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PUSH_PATH = Pattern.compile("/streams/[^/]+/records");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    /** Byte cap default (8 MiB); {@code -Dstreams.push.max_bytes} overrides. The body is buffered. */
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    /** Record cap default; {@code -Dstreams.push.max_records} overrides. */
    static final int DEFAULT_MAX_RECORDS = 100_000;

    /** Idempotency keys whose first request is still running (see the class doc). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Override
    public void register(ApiContext api) {
        api.post("/streams/([^/]+)/records", ApiContext.withCapability("canOperateRuns",
                (e, m) -> push(api, e, ApiContext.name(m))));
    }

    /** Whether {@code path} (prefixes stripped) is the push route — the rate limiter's selector. */
    static boolean isPushPath(String path) {
        return path != null && PUSH_PATH.matcher(path).matches();
    }

    enum Format { NDJSON, CSV }

    private Object push(ApiContext api, HttpExchange ex, String streamId) throws IOException {
        String pipeline = HostContext.of(api).service().pipelineForSourceId(streamId)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no Stream '" + streamId + "'"));
        PipelineConfig cfg = HostContext.of(api).service().configFor(pipeline)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no Stream '" + streamId + "'"));
        if (cfg.collector().hasDataset())
            throw new ApiException(501, ErrorCodes.NOT_SUPPORTED, "Stream '" + streamId + "' is fed by the Dataset '"
                    + cfg.collector().dataset() + "' — it has no inbox to push into");
        if (cfg.template())
            throw new ApiException(409, ErrorCodes.CONFLICT, "Pipeline '" + pipeline + "' is an authoring template — it never runs");
        // A paused or inactive Pipeline would let the batch sit in the inbox unseen; refuse, so the producer
        // retries later instead of believing its records are on their way (409, never 202-and-strand).
        boolean paused = HostContext.of(api).service().pipelines().stream().anyMatch(v -> v.name().equals(pipeline) && v.paused());
        if (paused || !cfg.active())
            throw new ApiException(409, ErrorCodes.CONFLICT, "pipeline paused: '" + pipeline + "' is "
                    + (paused ? "paused" : "not active") + " — retry once it runs");
        String poll = cfg.dirs().poll();
        if (poll == null || poll.isBlank())
            throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST, "Pipeline '" + pipeline + "' declares no dirs.poll");

        Format format = formatOf(ex.getRequestHeaders().getFirst("Content-Type"));
        // A keyed body the W5 stage could not hash (over Idempotency.MAX_REQUEST_BYTES) runs UN-keyed there, so a
        // retry would land the batch twice. Refuse it instead: a keyed push must fit the hash window.
        if (Idempotency.headerKey(ex, "POST") != null
                && "false".equals(ex.getResponseHeaders().getFirst(Idempotency.HEADER_CACHED)))
            throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "a push carrying an Idempotency-Key must be at most "
                    + Idempotency.MAX_REQUEST_BYTES + " bytes — split the batch, or send it without a key");
        // Read (capped) BEFORE the in-flight check: answering a duplicate while its body is still unread makes
        // the JDK server reset the connection, so the client sees a dropped socket instead of the 409.
        byte[] body = stripBom(api.rawBody(ex, Integer.getInteger("streams.push.max_bytes", DEFAULT_MAX_BYTES)));
        // The W5 key is already scoped to Space + principal + path (SEC-IDEMPOTENCY-REPLAY-1), so reserving it
        // scopes the in-flight fence to the caller too: two callers' equal keys never collide.
        Idempotency.Pending pending = ApiContext.attr(ex, ApiContext.ATTR_IDEMPOTENCY_KEY) instanceof Idempotency.Pending p ? p : null;
        String key = pending == null ? null : pending.key();
        if (key != null && !inFlight.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT,
                    "a request with this Idempotency-Key is still in flight — retry to receive its result");
        try {
            // The first request may have FINISHED while this one was reading its body — after the idempotency
            // stage looked and missed. Its captured result is authoritative: replay it rather than land again.
            if (pending != null) {
                Idempotency.Entry done = pending.store().get(pending.principal(), key);
                if (done != null && done.bodyHash().equals(pending.bodyHash())) {
                    Idempotency.replay(ex, done);
                    return ApiContext.HANDLED;
                }
            }
            int maxRecords = Integer.getInteger("streams.push.max_records", DEFAULT_MAX_RECORDS);
            String text = utf8(body);
            int records = format == Format.NDJSON ? validateNdjson(text, maxRecords)
                    : validateCsv(text, delimiterOf(cfg), maxRecords);
            if (records == 0)
                throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST, "the request carries no records");

            Path inbox = Paths.get(poll).toAbsolutePath().normalize();
            Path target = inbox.resolve(mintName(format, cfg, inbox));
            AtomicFiles.write(target, body, ".push-");   // stage a .tmp sibling (in-flight excluded), then move

            ApiContext.attr(ex, ApiContext.ATTR_AUDIT_ATTRS, Map.of(
                    "records", records, "bytes", body.length, "file", target.getFileName().toString()));
            log.info("Push to Stream '{}' landed {} record(s), {} byte(s) as {}", streamId, records, body.length,
                    target.getFileName());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stream", streamId);
            out.put("pipeline", pipeline);
            out.put("file", target.getFileName().toString());
            out.put("records", records);
            out.put("bytes", body.length);
            return ApiContext.respondJson(ex, 201, out);
        } finally {
            if (key != null) inFlight.remove(key);
        }
    }

    /**
     * A leading UTF-8 byte-order mark is STRIPPED, for both formats, before validation and before the write:
     * left in, it would glue itself to the first CSV header / NDJSON key and fail downstream far from here.
     */
    static byte[] stripBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF
                ? java.util.Arrays.copyOfRange(b, 3, b.length) : b;
    }

    /** NDJSON for {@code application/x-ndjson} / {@code application/jsonl}; CSV for {@code text/csv}; else 415. */
    static Format formatOf(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("ndjson") || ct.contains("jsonl") || ct.contains("json-lines")) return Format.NDJSON;
        if (ct.startsWith("text/csv") || ct.contains("/csv")) return Format.CSV;
        throw new ApiException(415, ErrorCodes.NOT_SUPPORTED,
                "Content-Type must be application/x-ndjson or text/csv, not '" + contentType + "'");
    }

    private static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException bad) {
            throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST, "the body is not valid UTF-8");
        }
    }

    /** Every non-blank line must be one JSON object. Returns the record count; 413 past {@code max}. */
    static int validateNdjson(String text, int max) {
        int records = 0;
        int lineNo = 0;
        for (String line : text.split("\n", -1)) {
            lineNo++;
            if (line.isBlank()) continue;
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (IOException bad) {
                node = null;
            }
            if (node == null || !node.isObject())
                throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST,
                        "line " + lineNo + " is not a JSON object — nothing was landed");
            if (++records > max) throw tooMany(max);
        }
        return records;
    }

    /**
     * RFC 4180 rows (quoted fields may hold the delimiter, doubled quotes and newlines). Every non-blank row must
     * have the first row's field count; an unterminated quote is malformed. Returns the row count (a header row
     * counts — the Pipeline's own {@code skip_header_lines} decides what it is); 413 past {@code max}.
     */
    static int validateCsv(String text, char delim, int max) {
        int rows = 0;
        int expected = -1;
        int line = 1;
        int rowStartLine = 1;
        int fields = 1;
        boolean quoted = false;
        boolean rowHasContent = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                    else quoted = false;
                } else if (c == '\n') line++;
                continue;
            }
            if (c == '"') { quoted = true; rowHasContent = true; }
            else if (c == delim) { fields++; rowHasContent = true; }
            else if (c == '\n') {
                if (rowHasContent) {
                    expected = checkRow(fields, expected, rowStartLine);
                    if (++rows > max) throw tooMany(max);
                }
                line++;
                rowStartLine = line;
                fields = 1;
                rowHasContent = false;
            } else if (c != '\r' && !Character.isWhitespace(c)) rowHasContent = true;
        }
        if (quoted)
            throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST,
                    "line " + rowStartLine + " opens a quoted field that never closes — nothing was landed");
        if (rowHasContent) {
            checkRow(fields, expected, rowStartLine);
            if (++rows > max) throw tooMany(max);
        }
        return rows;
    }

    private static int checkRow(int fields, int expected, int lineNo) {
        if (expected >= 0 && fields != expected)
            throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST, "line " + lineNo + " has " + fields
                    + " field(s), the first row has " + expected + " — nothing was landed");
        return fields;
    }

    private static ApiException tooMany(int max) {
        return new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "more than " + max + " records in one push");
    }

    private static char delimiterOf(PipelineConfig cfg) {
        String d = cfg.csv() == null ? null : cfg.csv().delimiter();
        return d == null || d.isEmpty() ? ',' : d.charAt(0);
    }

    /**
     * A unique server-minted name, {@code push-<utc stamp>-<8 hex>.<ext>}, whose extension the Pipeline's
     * {@code processing.file_pattern} accepts (matched against the absolute inbox path, as discovery does).
     * 422 when no extension for the format would ever be picked up — the batch would sit in the inbox forever.
     */
    private static String mintName(Format format, PipelineConfig cfg, Path inbox) {
        String stem = "push-" + ZonedDateTime.now(ZoneOffset.UTC).format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        List<String> exts = format == Format.NDJSON ? List.of("ndjson", "jsonl", "json") : List.of("csv", "txt");
        PathMatcher m = matcher(cfg.processing().filePattern());
        for (String ext : exts) {
            String name = stem + "." + ext;
            if (m.matches(inbox.resolve(name))) return name;
        }
        throw new ApiException(422, ErrorCodes.MALFORMED_REQUEST, "the Pipeline's file_pattern '"
                + cfg.processing().filePattern() + "' would never ingest a pushed " + format + " file (tried ."
                + String.join(", .", exts) + ")");
    }

    /** The {@code LocalFileSystemConnector} defaulting: explicit syntax verbatim, a path glob, else a name glob. */
    private static PathMatcher matcher(String pattern) {
        String p = pattern == null || pattern.isBlank() ? "glob:**/*.{csv,csv.gz}" : pattern.trim();
        String sp = p.startsWith("glob:") || p.startsWith("regex:") ? p
                : p.indexOf('/') >= 0 ? "glob:" + p : "glob:**/" + p;
        return FileSystems.getDefault().getPathMatcher(sp);
    }
}
