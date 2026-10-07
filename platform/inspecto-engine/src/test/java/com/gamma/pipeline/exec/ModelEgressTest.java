package com.gamma.pipeline.exec;

import com.gamma.util.egress.EgressPolicy;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Round-2 verification item 2 (2026-09-29): the model endpoint allowlist that stops SSRF through the model baseUrl. */
class ModelEgressTest {

    /** A resolver answering from a fixed table, so no test touches DNS. */
    private static EgressPolicy.Resolver table(Map<String, String> answers) {
        return host -> {
            String ip = answers.get(host);
            if (ip == null) throw new java.net.UnknownHostException(host);
            return new InetAddress[] {InetAddress.ofLiteral(ip)};
        };
    }

    private static final EgressPolicy.Resolver DNS = table(Map.of(
            "localhost", "127.0.0.1", "127.0.0.1", "127.0.0.1", "ollama.lan", "10.1.2.3",
            "metadata.evil", "169.254.169.254", "models.example", "93.184.216.34", "93.184.216.34", "93.184.216.34"));

    private static String pin(String url, String... entries) throws EgressPolicy.Refused {
        return ModelEgress.pin(url, ModelEgress.parse(List.of(entries)), DNS);
    }

    @Test
    void anEmptyListAllowsNoModelEndpointAtAll() {
        for (String url : List.of("http://localhost:11434", "http://ollama.lan:11434", "http://models.example",
                "http://93.184.216.34"))
            assertThrows(EgressPolicy.Refused.class, () -> pin(url), url);
    }

    @Test
    void loopbackIsReachableOnlyWhenTheListNamesItExplicitly() throws Exception {
        assertThrows(EgressPolicy.Refused.class, () -> pin("http://localhost:11434", "ollama.lan"));
        assertEquals("http://127.0.0.1:11434", pin("http://localhost:11434", "localhost"), "pinned to the checked address");
        assertEquals("http://127.0.0.1:11434/api", pin("http://127.0.0.1:11434/api", "127.0.0.1"));
    }

    @Test
    void aPrivateHostNeedsItsOwnEntryAndIsPinned() throws Exception {
        assertThrows(EgressPolicy.Refused.class, () -> pin("http://ollama.lan:11434", "localhost"));
        assertEquals("http://10.1.2.3:11434", pin("http://ollama.lan:11434", "ollama.lan"));
        assertEquals("http://10.1.2.3:11434", pin("http://ollama.lan:11434", "10.1.0.0/16"));
    }

    @Test
    void theMetadataServiceAndOddUrlsAreNeverReachable() {
        assertThrows(IllegalArgumentException.class, () -> ModelEgress.parse(List.of("169.254.169.254")));
        assertThrows(EgressPolicy.Refused.class, () -> pin("http://metadata.evil", "metadata.evil"));
        assertThrows(EgressPolicy.Refused.class, () -> pin("http://user@models.example", "models.example"));
        assertThrows(EgressPolicy.Refused.class, () -> pin("file:///etc/passwd", "localhost"));
        assertThrows(EgressPolicy.Refused.class, () -> pin("http://unknown.host", "unknown.host"));
    }

    @Test
    void anHttpsEndpointByNameCannotBePinnedSoItIsRefused() throws Exception {
        assertThrows(EgressPolicy.Refused.class, () -> pin("https://models.example", "models.example"));
        assertEquals("https://93.184.216.34", pin("https://93.184.216.34", "93.184.216.34"));
    }
}
