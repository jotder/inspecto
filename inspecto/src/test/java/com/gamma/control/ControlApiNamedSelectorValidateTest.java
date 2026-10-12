package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A delimited schema column whose {@code selector} is a NAME (not a zero-based index) is refused at config
 * load, so {@code POST /validate} with a {@code configPath} answers 422 with a plain message instead of the
 * failure surfacing at ingest as a scrubbed NumberFormatException.
 */
class ControlApiNamedSelectorValidateTest {

    @Test
    void validateAnswers422NamingTheColumnAndTheBadSelector(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path schema = cfg.resolve("mini_schema.toon");
        String text = Files.readString(schema);
        assertTrue(text.contains("AMT,\"1\",DOUBLE"), "fixture drifted: " + text);
        Path disk = Files.createDirectories(cfg.resolve("disk"));
        Path onDisk = PipelineConfigBatchTest.writePipeline(disk, "");
        Files.writeString(disk.resolve("mini_schema.toon"), text.replace("AMT,\"1\",DOUBLE", "AMT,\"AMT\",DOUBLE"));

        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/validate"))
                            .POST(BodyPublishers.ofString("{\"configPath\":\""
                                    + onDisk.toString().replace('\\', '/') + "\"}")).build(),
                    BodyHandlers.ofString());
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("column 'AMT'") && r.body().contains("selector 'AMT'")
                    && r.body().contains("zero-based column index"), r.body());
        } finally {
            api.close();
            svc.close();
        }
    }
}
