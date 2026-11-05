package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LA-ARRIVAL-NEW-COLUMNS-1 (operator 2026-11-06, plan D-6): a later file's NEW columns are kept as details of an import
 * Dataset. A run keeps the new columns EVERY file it takes carries (for delimited text: the same leading new columns, in
 * the same order, since the engine reads by position); the import's schema grows by each (read as text), and the Dataset
 * gains it with role {@code detail} and {@code keptFrom {file, at}}. Older Parquet files lack the column: every read
 * unions by name, so it reads as empty there. Once kept, a later file without it is refused like any missing column.
 *
 * <p>A new column is left out (as before: delimited text is rewritten with the columns read, and the result and the
 * arrival name it) when the person chose so on Add a file, when another file of the same run lacks it, when its name is
 * taken or cannot name a column, or when the Space's approval policy holds Dataset or schema changes. Every write is
 * undone with the run.
 */
final class DatasetNewColumns {
    private DatasetNewColumns() {}

    /** On a Dataset column a later file added: {@code {file, at}}. */
    static final String KEPT_FROM = "keptFrom";
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /** A file this run takes, accepted by {@link OnboardImport#checkColumns}. */
    record Taken(Path dir, Path file, String name, OnboardImport.ColumnCheck check) {}

    /** What the run kept ({@code kept}), and why any new column was left out ({@code reason}, null when none was). */
    record Outcome(List<String> kept, String reason) {
        static final Outcome NONE = new Outcome(List.of(), null);
    }

    /**
     * Decide, write and trim. {@code leftOut} receives, by file name, the new columns each file carries that were left
     * out. {@code keep} false: the person chose to leave new columns out.
     */
    static Outcome settle(OnboardImport.Space space, String id, String pipeline, boolean delimited, List<Taken> files,
                          boolean keep, Deque<OnboardImport.Undo> undo, Map<String, List<String>> leftOut) throws IOException {
        if (files.stream().allMatch(t -> t.check().freshCols().isEmpty())) return Outcome.NONE;
        Path schemaFile = space.writeRoot().resolve(OnboardImport.CONFIG_DIR).resolve(pipeline + "_schema.toon");
        Path pipelineFile = schemaFile.resolveSibling(pipeline + "_pipeline.toon");
        ComponentStore registry = new ComponentStore(space.writeRoot().resolve("registry"));
        Map<String, Object> dataset = registry.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);

        String reason = null;
        List<OnboardDraft.Col> kept = new ArrayList<>();
        if (!keep) reason = "you chose to leave new columns out";
        else if (dataset == null) reason = "'" + id + "' is not registered";
        else if (PendingChanges.governs(space.writeRoot(), "dataset") || PendingChanges.governs(space.writeRoot(), "schema"))
            reason = "this Space's approval policy holds changes to Datasets and schemas, so a column is added only on its own";
        else {
            Set<String> taken = takenNames(dataset, schemaFile);
            List<OnboardDraft.Col> first = files.get(0).check().freshCols();
            for (int i = 0; i < first.size(); i++) {
                OnboardDraft.Col c = first.get(i);
                boolean everywhere = true;
                for (Taken t : files) {
                    List<OnboardDraft.Col> f = t.check().freshCols();
                    everywhere &= delimited ? i < f.size() && f.get(i).name().equals(c.name())
                            : f.stream().anyMatch(x -> x.selector().equals(c.selector()));
                }
                String why = !everywhere ? "not every file this run took has it"
                        : !COLUMN.matcher(c.name()).matches() || !free(taken, c.name()) ? "a column of that name cannot be added"
                        : null;
                if (why == null) {
                    kept.add(c);
                    taken.add(c.name().toLowerCase(Locale.ROOT));
                } else {
                    if (reason == null) reason = why;
                    if (delimited) break;   // read by position: only the leading new columns can be kept
                }
            }
            if (!kept.isEmpty()) {
                String grownBy = grow(space, id, schemaFile, pipelineFile, registry, dataset, kept, files, delimited, undo);
                if (grownBy != null) {
                    reason = grownBy;
                    kept.clear();
                }
            }
        }

        List<String> keptNames = kept.stream().map(OnboardDraft.Col::name).toList();
        for (Taken t : files) {
            OnboardImport.ColumnCheck c = t.check();
            List<String> out = new ArrayList<>();
            for (OnboardDraft.Col f : c.freshCols()) if (!keptNames.contains(f.name())) out.add(f.name());
            if (out.isEmpty()) continue;
            leftOut.put(t.name(), out);
            if (delimited) {   // the engine sets aside rows with more fields than it reads
                List<String> read = new ArrayList<>(c.keep());
                for (int i = 0; i < kept.size(); i++) read.add(c.freshCols().get(i).header());
                OnboardImport.keepReadColumns(t.dir(), t.file(), c.withKeep(read));
            }
        }
        return new Outcome(keptNames, leftOut.isEmpty() ? null : reason);
    }

    private static boolean free(Set<String> taken, String name) {
        return !taken.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The Dataset's column names and every field the schema reads (a column dropped at commit is read too), lower-case. */
    private static Set<String> takenNames(Map<String, Object> dataset, Path schemaFile) {
        Set<String> out = new HashSet<>();
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols) if (o instanceof Map<?, ?> m) out.add(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT));
        try {
            Map<String, Object> s = com.gamma.util.ToonHelper.load(schemaFile.toString());
            if (s.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fs)
                for (Object f : fs) if (f instanceof Map<?, ?> fm) out.add(String.valueOf(fm.get("name")).toLowerCase(Locale.ROOT));
            if (s.get("mapping") instanceof Map<?, ?> map && map.get("fields") instanceof List<?> fs)
                for (Object f : fs) if (f instanceof Map<?, ?> fm) out.add(String.valueOf(fm.get("name")).toLowerCase(Locale.ROOT));
        } catch (Exception unreadable) {
            // checkColumns already refused an unreadable schema
        }
        return out;
    }

    /**
     * The schema reads each kept column (text) and maps it through; the Pipeline is re-read; the Dataset lists it as a
     * detail. Answers null, or why nothing was kept (the schema did not load: every write here is put back).
     */
    @SuppressWarnings("unchecked")
    private static String grow(OnboardImport.Space space, String id, Path schemaFile, Path pipelineFile, ComponentStore registry,
                               Map<String, Object> dataset, List<OnboardDraft.Col> kept, List<Taken> files, boolean delimited,
                               Deque<OnboardImport.Undo> undo) throws IOException {
        byte[] before = Files.readAllBytes(schemaFile);
        Map<String, Object> schema;
        try {
            schema = new LinkedHashMap<>(com.gamma.util.ToonHelper.load(schemaFile.toString()));
        } catch (Exception unreadable) {
            return "the schema of '" + id + "' cannot be read";
        }
        Map<String, Object> raw = new LinkedHashMap<>((Map<String, Object>) schema.get("raw"));
        Map<String, Object> mapping = new LinkedHashMap<>((Map<String, Object>) schema.get("mapping"));
        List<Object> fields = new ArrayList<>((List<Object>) raw.get("fields"));
        List<Object> mapped = new ArrayList<>((List<Object>) mapping.get("fields"));
        int next = fields.size();   // delimited: the fields read are columns 0 .. size-1, the new ones follow
        for (OnboardDraft.Col c : kept) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", c.name());
            f.put("selector", delimited ? String.valueOf(next++) : c.selector());
            f.put("type", "VARCHAR");
            fields.add(f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("from", c.name());
            m.put("fn", "keep");
            mapped.add(m);
        }
        raw.put("fields", fields);
        mapping.put("fields", mapped);
        schema.put("raw", raw);
        schema.put("mapping", mapping);
        AtomicFiles.write(schemaFile, ConfigCodec.toToon(schema).getBytes(StandardCharsets.UTF_8), ".cfg-");
        undo.push(() -> {
            AtomicFiles.write(schemaFile, before, ".cfg-");
            space.service().refreshConfigs();
        });
        try {
            PipelineConfig.load(pipelineFile.toString());
        } catch (Exception refused) {
            AtomicFiles.write(schemaFile, before, ".cfg-");
            return "the import's schema did not load with it (" + refused.getMessage() + ")";
        }
        space.service().refreshConfigs();

        String file = files.stream().filter(t -> !t.check().freshCols().isEmpty()).map(Taken::name).findFirst().orElse(null);
        String at = Instant.now().toString();
        List<Object> cols = new ArrayList<>(dataset.get("columns") instanceof List<?> l ? l : List.of());
        for (OnboardDraft.Col c : kept) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", OnboardImport.datasetType("VARCHAR"));
            col.put("role", "detail");
            Map<String, Object> from = new LinkedHashMap<>();
            if (file != null) from.put("file", file);
            from.put("at", at);
            col.put(KEPT_FROM, from);
            cols.add(col);
        }
        Map<String, Object> content = new LinkedHashMap<>(dataset);
        content.remove("name");
        content.put("columns", cols);
        Map<String, Object> prior = new LinkedHashMap<>(dataset);
        prior.remove("name");
        registry.write("dataset", id, content);
        undo.push(() -> {
            Map<String, Object> now = registry.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
            if (now == null) return;
            Map<String, Object> back = new LinkedHashMap<>(now);
            back.remove("name");
            back.put("columns", prior.get("columns"));
            registry.write("dataset", id, back, false);
        });
        return null;
    }

    /**
     * The refusal note for a missing column a later file added: {@code (kept from 'wider.csv' on 2026-11-06)}, by column
     * name; empty when the Dataset lists none.
     */
    static Map<String, String> keptNotes(Map<String, Object> dataset) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dataset == null || !(dataset.get("columns") instanceof List<?> cols)) return out;
        for (Object o : cols)
            if (o instanceof Map<?, ?> m && m.get(KEPT_FROM) instanceof Map<?, ?> k) {
                String at = k.get("at") instanceof String a && a.length() >= 10 ? " on " + a.substring(0, 10) : "";
                String file = k.get("file") != null ? " from '" + k.get("file") + "'" : " from a later file";
                out.put(String.valueOf(m.get("name")), "kept" + file + at);
            }
        return out;
    }
}
