package com.gamma.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

/**
 * A Space's secret keys, kept OUTSIDE its config tree in the sibling directory {@code <config root>.secrets/} —
 * for a hosted Space {@code <space>/config.secrets/}, for the default Space {@code <assist.write.root>.secrets/}.
 * That directory is outside every exported, imported and shared tree; {@code BackupTask} skips any
 * {@code *.secrets} directory, {@code PathJail} refuses to resolve into one, and {@code .gitignore} ignores it.
 *
 * <p>Extracted from the Pending Change key (ASSURE-MAKER-CHECKER-1) so every Space key shares ONE creation rule:
 * 32 random bytes, created with {@code CREATE_NEW} so exactly one writer ever creates it (a racer reads the
 * winner's key; nothing ever replaces a key), owner-only where the platform allows it (POSIX {@code rw-------},
 * or an owner-only ACL on Windows), and read back with a brief retry while the first writer is mid-write.
 */
public final class SpaceSecretKeys {

    /** The suffix of the per-config-root secrets directory. */
    public static final String SECRETS_SUFFIX = ".secrets";

    private SpaceSecretKeys() {}

    /** {@code <config root>.secrets/<name>}. */
    public static Path keyFile(Path configRoot, String name) {
        Path config = configRoot.toAbsolutePath().normalize();
        if (config.getParent() == null || config.getFileName() == null)
            throw new IllegalStateException("the config root " + config + " has no parent to hold its secrets");
        return config.resolveSibling(config.getFileName() + SECRETS_SUFFIX).resolve(name);
    }

    /** The key in {@code f}, creating it first-writer-wins when absent. {@code what} names it in errors. */
    public static byte[] readOrCreate(Path f, String what) throws IOException {
        Files.createDirectories(f.getParent());
        // No exists() pre-check: EVERY first use attempts CREATE_NEW, and the filesystem decides the one winner.
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        byte[] hex = HexFormat.of().formatHex(k).getBytes(StandardCharsets.US_ASCII);
        boolean posix = f.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        try (var ch = Files.newByteChannel(f, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attrs)) {
            if (!posix) ownerOnlyAcl(f);
            ch.write(ByteBuffer.wrap(hex));
        } catch (FileAlreadyExistsException raced) {
            // another writer created it first — theirs is the key; read it below
        }
        for (int attempt = 0; ; attempt++) {
            String text = Files.readString(f, StandardCharsets.US_ASCII).trim();
            if (text.length() == 64) return HexFormat.of().parseHex(text);
            if (attempt >= 200) throw new IOException("the " + what + " " + f + " is not a whole key");
            try {
                Thread.sleep(5);   // the first writer is between CREATE_NEW and its write
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted reading the " + what, stop);
            }
        }
    }

    /** Windows: an ACL with one entry — the file's owner, full control — replacing whatever it inherited. */
    public static void ownerOnlyAcl(Path f) {
        var view = Files.getFileAttributeView(f, AclFileAttributeView.class);
        if (view == null) return;   // neither POSIX nor ACL: nothing narrower to set
        try {
            var owner = view.getOwner();
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            view.setAcl(List.of(entry));
        } catch (IOException | RuntimeException bestEffort) {
            // a filesystem that refuses an ACL change keeps its default permissions; the key is still outside
            // every exported, imported and backed-up tree
        }
    }
}
