package com.gamma.inspector;

import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run-time backstop of `PROCESSOR-RELEASE-READINESS-1` G9 for the CLI entry points the pipeline registry
 * does not guard: on a Personal build (this module's classpath) a remote Collector with the Professional+
 * {@code post_action: MOVE} archive is refused with {@code ERR_EDITION_FEATURE} before any connector opens —
 * never acquired with the move silently skipped. The Professional run of the same shape is
 * {@code CollectorProcessorRemoteCycleTest}'s.
 */
class CollectorProcessorArchiveEditionTest {

    @Test
    void aMoveArchiveIsRefusedBeforeAcquiring(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(Map.of(
                "name", "ARCHIVE_ETL",
                "dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()),
                "processing", Map.of("threads", 1),
                "collector", Map.of("connector", "sftp",
                        "post_action", Map.of("on_success", "MOVE", "archive_path", "archive"))));
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> CollectorProcessor.acquire(cfg, false));
        assertTrue(boom.getMessage().startsWith(EditionFeatures.CODE), boom.getMessage());
        assertTrue(boom.getMessage().contains("sink.archive"), boom.getMessage());
    }
}
