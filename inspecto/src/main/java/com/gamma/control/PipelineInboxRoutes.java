package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A Pipeline's inbox ({@code dirs.poll}) from the editor: list what is waiting there, and upload one file into
 * it (INBOX-UPLOAD-1, builder pilot 2026-09-25). A Stream onboarded from the Catalog polls
 * {@code data/inbox/<stream>} under the Space root and binds no connection, so before this route the only way to
 * give its first run a file was to copy one into that directory by hand.
 *
 * <p>The upload is the RAW request body (any content type), named by {@code ?file=}. The name is a BARE file name
 * — a separator, {@code ..} or a drive/stream colon is a 403, never resolved — and the resolved target is jailed
 * to the poll directory anyway. The write stages a {@code .tmp} sibling (the poll's own in-flight exclusion) and
 * moves it into place, so a poll cycle never sees a half-written file.
 */
final class PipelineInboxRoutes implements RouteModule {

    /** Upload cap. The body is buffered, so this bounds the heap one request can take. */
    static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;
    /** Listing cap; {@code truncated} + {@code total} report the true size. */
    static final int MAX_LISTED = 500;

    @Override
    public void register(ApiContext api) {
        api.get("/pipelines/authored/([^/]+)/inbox", (e, m) -> list(api, ApiContext.name(m)));
        // canAuthorWorkbench, like run-to-here: the affordance lives in the editor's build-and-test loop.
        api.post("/pipelines/authored/([^/]+)/inbox", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> upload(api, e, ApiContext.name(m))));
    }

    /**
     * {@code GET /pipelines/authored/{id}/inbox} — the regular files directly in the poll directory, newest
     * first, by name (the name is what {@code POST …/run} takes for a Pipeline that binds no connection).
     * 404 unknown Pipeline · 501 a Dataset-fed Pipeline (it has no inbox). An absent directory lists empty.
     */
    private Object list(ApiContext api, String id) throws IOException {
        Path inbox = inboxOf(configOf(api, id));
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(inbox)) {
            try (Stream<Path> s = Files.list(inbox)) {
                for (Path p : s.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(PipelineInboxRoutes::mtime).reversed()).toList()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", p.getFileName().toString());
                    f.put("size", Files.size(p));
                    f.put("modifiedAt", Files.getLastModifiedTime(p).toInstant().toString());
                    files.add(f);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", id);
        out.put("inbox", inbox.toString());
        out.put("total", files.size());
        out.put("truncated", files.size() > MAX_LISTED);
        out.put("files", files.size() > MAX_LISTED ? files.subList(0, MAX_LISTED) : files);
        return out;
    }

    /**
     * {@code POST /pipelines/authored/{id}/inbox?file=<name>[&overwrite=true]} — land the raw body as
     * {@code <dirs.poll>/<name>}. Gates, in order: 503 no write root · 404 unknown Pipeline · 501 a Dataset-fed
     * Pipeline · 422 no/blank name · 403 a name that is a path or escapes the poll directory · 413 over
     * {@link #MAX_UPLOAD_BYTES} · 422 an empty body · 409 the file exists and {@code overwrite} is not
     * {@code true} · then an atomic temp+move write.
     */
    private Object upload(ApiContext api, HttpExchange ex, String id) throws IOException {
        if (api.writeRoot() == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY,
                    "no write root configured (-Dassist.write.root) — uploads are disabled");
        Path inbox = inboxOf(configOf(api, id));

        String name = ApiContext.query(ex, "file");
        if (name == null || name.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query parameter 'file' (the file name) is required");
        name = name.trim();
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.equals(".") || name.contains("..")
                || name.chars().anyMatch(ch -> ch < 0x20))
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'file' must be a bare file name, not a path: " + name);
        Path target = inbox.resolve(name).normalize();
        if (!target.getParent().equals(inbox))
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'" + name + "' escapes the pipeline's inbox");

        byte[] bytes = api.rawBody(ex, MAX_UPLOAD_BYTES);
        if (bytes.length == 0)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the uploaded file is empty");

        boolean overwrite = "true".equalsIgnoreCase(ApiContext.query(ex, "overwrite"));
        boolean existed = Files.exists(target);
        if (existed && !overwrite)
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + name + "' is already in the inbox; pass overwrite=true to replace it");
        if (existed && !Files.isRegularFile(target))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + name + "' exists in the inbox and is not a file");

        AtomicFiles.write(target, bytes, ".upload-");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", id);
        out.put("file", name);
        out.put("size", bytes.length);
        out.put("replaced", existed);
        return out;
    }

    private static PipelineConfig configOf(ApiContext api, String id) {
        return api.service().configFor(id)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no authored pipeline '" + id + "'"));
    }

    /** The poll directory, resolved the way the watcher resolves it ({@code CollectorWatcher}). */
    private static Path inboxOf(PipelineConfig cfg) {
        if (cfg.collector().hasDataset())
            throw new ApiException(501, ErrorCodes.NOT_SUPPORTED, "this pipeline is fed by the Dataset '"
                    + cfg.collector().dataset() + "' — it has no inbox");
        String poll = cfg.dirs().poll();
        if (poll == null || poll.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this pipeline declares no dirs.poll");
        return Paths.get(poll).toAbsolutePath().normalize();
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
