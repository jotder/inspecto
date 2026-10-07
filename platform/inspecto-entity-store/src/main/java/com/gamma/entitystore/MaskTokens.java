package com.gamma.entitystore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The keyed pseudonym an Entity List member (or an Investigation entity id) shows as when its Entity Type is masked
 * (LA-19, decision D-U6): {@code masked:<16 hex>}, an HMAC-SHA256 of the id under a random key. Keyed, because a plain
 * hash of a phone number is reversible by enumerating the number space.
 *
 * <p>Split out of Link Analysis' {@code EntityMasking} (SEP-08) so {@link EntityListRoutes} can mask members without
 * depending on the Investigation renderer: the algorithm, the {@code mask.key} file name and the token format are
 * unchanged, so a pseudonym minted before the split is the pseudonym minted after it.
 */
public final class MaskTokens {

    public static final String TOKEN_PREFIX = "masked:";
    private static final String KEY_FILE = "mask.key";

    private MaskTokens() {
    }

    /** The HMAC key in {@code dir}, created on first use. Never served. {@link EntityListRoutes} keys
     *  Entity List members with one key per Space fact log; Link Analysis keys one per Investigation. ⚠ The key is
     *  written to a sibling temp file and only then published by {@link EntityFactLog#publishNew} (a hard link, never
     *  a replace), so a concurrent first reader never sees a partly written {@code mask.key}; the loser of a race
     *  reads the winner's. */
    public static byte[] key(Path dir) throws IOException {
        Path f = dir.resolve(KEY_FILE);
        if (!Files.isRegularFile(f)) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            Path tmp = Files.createTempFile(dir, ".mask-", ".tmp");
            try {
                Files.writeString(tmp, HexFormat.of().formatHex(k), StandardCharsets.UTF_8);
                EntityFactLog.publishNew(tmp, f);
            } catch (FileAlreadyExistsException raced) {
                // another request created it first — read theirs below
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return HexFormat.of().parseHex(Files.readString(f, StandardCharsets.UTF_8).trim());
    }

    public static String token(byte[] key, String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return TOKEN_PREFIX + HexFormat.of().formatHex(mac.doFinal(id.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
