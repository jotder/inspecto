package com.gamma.intelligence.natives;

import com.eoiagent.knowledge.OnnxEmbeddingAdapter;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the platform's onnxruntime + DJL tokenizers natives for real (NATIVE-LICENCE-LINUX-MACOS-1):
 * one string through {@link OnnxEmbeddingAdapter}, the eoiagent seam the sidecar uses. On the Linux CI job
 * this loads {@code libonnxruntime.so}, {@code libonnxruntime4j_jni.so} and {@code libtokenizers.so}; on
 * Linux the system libstdc++ is used (the Windows-only MinGW DLLs are not involved). A native that loads
 * but cannot run fails here. Skipped only when the jars carry no native for this os/arch.
 */
class NativeEmbeddingLoadTest {

    @Test
    void embedsOneStringThroughThePlatformNatives() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String ortOs = os.contains("win") ? "win" : os.contains("mac") ? "osx" : "linux";
        String ortArch = arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : arch;
        String probe = "ai/onnxruntime/native/" + ortOs + "-" + ortArch + "/";
        boolean present = getClass().getClassLoader().getResource(probe) != null;
        if (!present) System.out.println("NativeEmbeddingLoadTest SKIPPED: no onnxruntime native at " + probe);
        assumeTrue(present, "no onnxruntime native for " + ortOs + "-" + ortArch + " (" + probe + ")");

        Embedding e = new OnnxEmbeddingAdapter().embed("disk usage alert on node 7").content();
        assertEquals(OnnxEmbeddingAdapter.DIMENSION, e.dimension());
        float norm = 0;
        for (float v : e.vector()) norm += v * v;
        assertTrue(norm > 0.5f, "embedding vector is degenerate: |v|^2=" + norm);
        System.out.println("NativeEmbeddingLoadTest: " + ortOs + "-" + ortArch + " dim=" + e.dimension());
    }
}
