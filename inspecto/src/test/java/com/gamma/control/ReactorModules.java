package com.gamma.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Maven reactor's module directories from the poms themselves, so a guard that scans "every module"
 * does not depend on WHERE the module directories sit (repo root today, grouped one level down after a regroup).
 * The root is the OUTERMOST ancestor of {@code user.dir} whose {@code pom.xml} declares {@code <modules>}; module
 * directories are resolved recursively from every {@code <module>} entry in that pom — default list, every profile
 * list, and nested aggregators (e.g. {@code providers/asn-parser/asn-decoders}). Comments are stripped before reading.
 * Copy of the lookup the sibling-enumerating guards used to hand-roll ({@code Files.list("..")}).
 */
public final class ReactorModules {

    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private ReactorModules() {}

    /** The reactor root: the outermost ancestor of the working directory with a pom.xml declaring modules. */
    public static Path root() throws IOException {
        Path found = null;
        for (Path d = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            Path pom = d.resolve("pom.xml");
            if (Files.isRegularFile(pom) && !declared(pom).isEmpty()) found = d;
        }
        if (found == null) throw new IllegalStateException("no reactor root (pom.xml with <modules>) above " + System.getProperty("user.dir"));
        return found;
    }

    /** Every module directory (aggregators included, the root excluded), absolute and sorted. */
    public static List<Path> modules() throws IOException {
        Set<Path> out = new TreeSet<>();
        collect(root(), out);
        return new ArrayList<>(out);
    }

    /** {@link #modules()} minus any module that lives inside another module's directory (nested reactors' children). */
    public static List<Path> topLevelModules() throws IOException {
        List<Path> all = modules();
        List<Path> out = new ArrayList<>();
        for (Path m : all) if (all.stream().noneMatch(o -> !o.equals(m) && m.startsWith(o))) out.add(m);
        return out;
    }

    /** The modules among {@code dirs} that have a {@code src/main/java} tree. */
    public static List<Path> withMainJava(List<Path> dirs) {
        return dirs.stream().filter(p -> Files.isDirectory(p.resolve("src/main/java"))).toList();
    }

    /**
     * Every {@code src/main/java} tree to scan: each top-level module's, plus the tree of any pom-less directory that
     * sits ABOVE a module (the legacy {@code providers/asn-parser/src/main/java} beside its nested {@code asn-decoders} reactor).
     */
    public static List<Path> mainJavaTrees() throws IOException {
        Path root = root();
        Set<Path> dirs = new TreeSet<>();
        for (Path m : topLevelModules()) {
            dirs.add(m);
            for (Path a = m.getParent(); a != null && !a.equals(root) && a.startsWith(root); a = a.getParent()) dirs.add(a);
        }
        return withMainJava(new ArrayList<>(dirs)).stream().map(d -> d.resolve("src/main/java")).toList();
    }

    private static void collect(Path dir, Set<Path> out) throws IOException {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return;
        for (String m : declared(pom)) {
            Path child = dir.resolve(m).normalize();
            if (out.add(child)) collect(child, out);
        }
    }

    private static Set<String> declared(Path pom) throws IOException {
        String xml = COMMENT.matcher(Files.readString(pom)).replaceAll("");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MODULE.matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
