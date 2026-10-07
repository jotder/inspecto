package com.gamma.module;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The manifests of EVERY module in the source tree, installed or not (MODULE-REORG-1 P3b). The host's build copies
 * each module's {@code module.toon} into {@value #DIR}{@code <id>.toon} plus an {@value #INDEX} listing the ids
 * (tools/KnownModules.java, run from inspecto/pom.xml), so an install that left an optional module out can still
 * answer for its surface: the 503 stubs of an absent module are synthesised from these, and
 * {@code GET /modules} reports what is missing. Fail-soft per file, like {@link ModuleManifests}.
 */
public final class KnownModules {
    private KnownModules() {}

    public static final String DIR = "META-INF/inspecto/known-modules/";
    public static final String INDEX = DIR + "index.txt";

    /** Every known manifest, in index order; a missing index reads as none plus one diagnostic. */
    public static ModuleManifests.Loaded load(ClassLoader loader) {
        List<ModuleManifest> out = new ArrayList<>();
        List<String> diag = new ArrayList<>();
        URL index = loader.getResource(INDEX);
        if (index == null) return new ModuleManifests.Loaded(List.of(), List.of(INDEX + " is not on the class path"));
        String ids = read(index, diag);
        for (String id : ids.split("\\R")) {
            if (id.isBlank()) continue;
            URL url = loader.getResource(DIR + id.trim() + ".toon");
            if (url == null) {
                diag.add(DIR + id.trim() + ".toon: listed in the index but missing");
                continue;
            }
            for (String chunk : read(url, diag).split("(?m)^" + ModuleManifests.SEPARATOR + "[ \\t]*\\r?$")) {
                if (chunk.isBlank()) continue;
                try {
                    out.add(ModuleManifests.parse(chunk.strip()));
                } catch (RuntimeException e) {
                    diag.add(url + ": " + e.getMessage());
                }
            }
        }
        return new ModuleManifests.Loaded(List.copyOf(out), List.copyOf(diag));
    }

    /** The known manifests whose id is not among the installed ones. */
    public static List<ModuleManifest> absent(List<ModuleManifest> known, List<ModuleManifest> installed) {
        Set<String> have = new HashSet<>();
        for (ModuleManifest m : installed) have.add(m.id());
        return known.stream().filter(m -> !have.contains(m.id())).toList();
    }

    /** The 503 text for an absent module: its own {@code absentMessage}, else a sentence naming the module. */
    public static String absentMessage(ModuleManifest m) {
        return m.absentMessage() != null ? m.absentMessage()
                : m.title() + " is not installed in this bundle - provided by the optional " + m.id() + " module.";
    }

    private static String read(URL url, List<String> diag) {
        try (var in = ModuleManifests.openUncached(url)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            diag.add(url + ": unreadable - " + e.getMessage());
            return "";
        }
    }
}
