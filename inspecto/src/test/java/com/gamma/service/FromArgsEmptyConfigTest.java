package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for {@code LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1}: {@link CollectorService#fromArgs} is a
 * public API that tests and embedders call directly (as {@code WorkflowConfigLoadTest} did), yet an
 * empty-config invocation used to call {@code System.exit(1)} deep inside {@link ServiceBootstrap}
 * — killing the host JVM with no exception to catch, no stack trace, and a surefire message that
 * blames a VM crash rather than the real cause. That is exactly the failure mode that cost a prior
 * shift a full root-cause hunt ending in a wrong JDK-27 diagnosis (see docs/BACKLOG.md).
 *
 * <p>{@code fromArgs} must now throw a catchable {@link EmptyConfigException} instead of exiting the
 * process. The CLI {@code main} methods ({@link CollectorService#main} and
 * {@code com.gamma.control.ControlApi#main}) still exit 1 in this scenario — verified by inspection
 * below, not by a test, since driving a real {@code System.exit} from a unit test would kill the
 * test JVM itself.
 */
class FromArgsEmptyConfigTest {

    @Test
    void fromArgsThrowsInsteadOfExitingOnEmptyConfig(@TempDir Path emptyDir) {
        // An empty directory: no *_pipeline.toon / *_enrich.toon / *_job.toon anywhere under it.
        EmptyConfigException e = assertThrows(EmptyConfigException.class,
                () -> CollectorService.fromArgs(new String[]{emptyDir.toString()}),
                "fromArgs must throw a catchable exception, not System.exit(1), when config "
                        + "discovery finds nothing — a caller (test or embedder) has no way to catch "
                        + "a killed JVM");
        assertTrue(e.getMessage().contains(emptyDir.toString()),
                "the exception should name the scanned path, matching the message System.exit(1) used to print");
    }

    @Test
    void buildFromThrowsDirectlyWhenExitIfEmptyIsSet(@TempDir Path emptyDir) {
        assertThrows(EmptyConfigException.class,
                () -> ServiceBootstrap.buildFrom(SpaceRoot.legacy(), new String[]{emptyDir.toString()}, true));
    }

    // NOTE (verified by inspection, not a runnable test — see class javadoc): CollectorService.main
    // and ControlApi.main each wrap their fromArgs()/buildFrom() call in a try/catch for
    // EmptyConfigException and call System.exit(1) in the catch block, so the CLI path's observable
    // behavior (process exits 1 on empty config) is unchanged; only WHERE the exit happens moved from
    // ServiceBootstrap.buildFrom up to the CLI entry points.
}
