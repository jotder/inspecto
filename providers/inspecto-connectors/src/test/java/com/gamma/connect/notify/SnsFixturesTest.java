package com.gamma.connect.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S0: the fixture set is self-consistent — every envelope parses, the test PKI issues a certificate that chains
 * to its CA, and a signed fixture verifies with nothing but the JDK (so the later adapter tests exercise the
 * adapter, not a broken fixture).
 */
class SnsFixturesTest {

    @Test
    void everyEnvelopeAndSesPayloadParsesAndTheSignatureVerifiesWithThePlainJdk() throws Exception {
        for (String name : List.of("subscription-confirmation", "unsubscribe-confirmation")) {
            assertSignedAndVerifiable(SnsFixtures.envelope(name));
        }
        for (String ses : List.of("bounce-permanent", "bounce-transient", "bounce-undetermined", "complaint",
                "delivery", "delivery-delay", "reject", "rendering-failure", "open", "legacy-bounce-permanent")) {
            JsonObject env = SnsFixtures.notification(ses);
            JsonObject inner = JsonParser.parseString(env.get("Message").getAsString()).getAsJsonObject();
            assertTrue(inner.has("mail"), ses);
            assertSignedAndVerifiable(env);
        }
    }

    @Test
    void theSignerChainsToTheTestCaAndNamesSns() throws Exception {
        var signer = SnsTestPki.signer();
        signer.cert().verify(SnsTestPki.ca().getPublicKey());
        assertTrue(signer.cert().getSubjectX500Principal().getName().contains("CN=sns.amazonaws.com"));
    }

    private static void assertSignedAndVerifiable(JsonObject env) throws Exception {
        byte[] raw = SnsFixtures.sign(env);
        JsonObject back = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(SnsTestPki.signer().cert());
        v.update(SnsFixtures.stringToSign(back).getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(Base64.getDecoder().decode(back.get("Signature").getAsString())), env.get("Type").toString());
    }
}
