package com.gamma.pack;

import java.nio.file.Path;

/** Public seam so the screening-package test copies the template the same way the golden test does. */
public final class AmlPackGoldenTestSupport {

    private AmlPackGoldenTestSupport() {}

    public static Path copy(Path tmp) throws Exception { return AmlPackGoldenTest.copyTemplate(tmp); }
}
