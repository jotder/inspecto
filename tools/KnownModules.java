import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies every module's {@code META-INF/inspecto/module.toon} in the source tree to {@code <out>/<id>.toon} and writes
 * {@code <out>/index.txt} (one id per line, sorted). Run by inspecto/pom.xml (exec-maven-plugin, java source launcher)
 * so the processor jar carries the manifests of modules a given edition leaves OUT (MODULE-REORG-1 P3b).
 *
 * <pre>java tools/KnownModules.java &lt;repoRoot&gt; &lt;outDir&gt;</pre>
 */
public class KnownModules {
    private static final Set<String> SKIP = Set.of("node_modules", "target", ".git", ".claude", ".codegraph", "dist");
    private static final String TAIL = "src/main/resources/META-INF/inspecto/module.toon";
    private static final Pattern ID = Pattern.compile("(?m)^id:\\s*\"?([A-Za-z0-9._-]+)\"?\\s*$");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Path.of(args[1]);
        TreeMap<String, Path> found = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                return !dir.equals(root) && SKIP.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!f.toString().replace('\\', '/').endsWith(TAIL)) return FileVisitResult.CONTINUE;
                Matcher m = ID.matcher(Files.readString(f, StandardCharsets.UTF_8));
                if (!m.find()) throw new IOException(f + ": no id: line");
                Path prev = found.put(m.group(1), f);
                if (prev != null) throw new IOException("duplicate module id " + m.group(1) + ": " + prev + " and " + f);
                return FileVisitResult.CONTINUE;
            }
        });
        if (Files.isDirectory(out))
            try (var s = Files.list(out)) { for (Path p : (Iterable<Path>) s::iterator) Files.delete(p); }
        Files.createDirectories(out);
        StringBuilder index = new StringBuilder();
        for (var e : found.entrySet()) {
            Files.write(out.resolve(e.getKey() + ".toon"), Files.readAllBytes(e.getValue()));
            index.append(e.getKey()).append('\n');
        }
        Files.writeString(out.resolve("index.txt"), index.toString(), StandardCharsets.UTF_8);
        System.out.println("known-modules: " + found.size() + " manifests -> " + out);
    }
}
